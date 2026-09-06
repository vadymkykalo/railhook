package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.UserSession;
import com.webhook.platform.api.domain.repository.UserSessionRepository;
import com.webhook.platform.api.dto.SessionResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The signed-in devices an account has, and the ability to end one of them.
 *
 * <p>Two stores, doing two different jobs, and it is worth being clear about which is which.
 * The rows here are the <em>list</em>: they survive a Redis flush, so a user can always see what
 * exists. {@link TokenBlacklistService} is the <em>enforcement</em>: a revoked session id goes
 * into Redis with a TTL and {@code JwtAuthenticationFilter} reads it on every request, which is
 * what makes an already-issued access token stop working immediately rather than in fifteen
 * minutes. Revoking writes to both, in that order, because a row marked revoked that Redis never
 * heard about is a lie the UI would tell, whereas a Redis marker with no row is merely untidy.
 *
 * <p>{@code UserSession} carries no {@code @TenantId} (see the entity for why), so nothing
 * confines these queries structurally. Every method that touches an existing session therefore
 * takes the caller's own user id and looks the session up <em>by that pair</em> — never by
 * session id alone. That is the whole of the ownership check, and it is why there is no method
 * here that accepts a session id without one.
 */
@Service
@Slf4j
public class UserSessionService {

    private final UserSessionRepository userSessionRepository;
    private final TokenBlacklistService tokenBlacklistService;

    public UserSessionService(UserSessionRepository userSessionRepository,
                              TokenBlacklistService tokenBlacklistService) {
        this.userSessionRepository = userSessionRepository;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * Records a newly minted refresh-token family.
     *
     * <p>Takes a built entity rather than a spread of arguments so that the organization a
     * session is scoped to arrives as a field of the thing being persisted, which is what it is —
     * see {@code ServiceTenantParameterTest} for why an {@code organizationId} parameter on a
     * service method is a different and worse thing.
     */
    @SystemTenant("a session belongs to a user across organizations; user_sessions is deliberately not tenant-scoped")
    @Transactional
    public UserSession open(UserSession session) {
        UserSession saved = userSessionRepository.save(session);
        log.debug("Opened session {} for user {} ({})", saved.getId(), saved.getUserId(), saved.getClient());
        return saved;
    }

    @SystemTenant("looked up by the refresh token alone, before any organization is established")
    @Transactional(readOnly = true)
    public Optional<UserSession> findByRefreshJti(String refreshTokenJti) {
        return userSessionRepository.findByRefreshTokenJti(refreshTokenJti);
    }

    /**
     * Moves a session onto the refresh token that just replaced its previous one.
     *
     * <p>The jti is the session's identity as far as the refresh path is concerned, so this and
     * the blacklisting of the old jti have to be the same event: a session still pointing at a
     * jti that has been blacklisted can never be refreshed again.
     */
    @SystemTenant("runs on the refresh path, which has no organization scope until it decides one")
    @Transactional
    public void rotate(UserSession session, String newRefreshTokenJti, Instant expiresAt, String ipAddress) {
        session.setRefreshTokenJti(newRefreshTokenJti);
        session.setExpiresAt(expiresAt);
        session.setLastSeenAt(Instant.now());
        if (ipAddress != null) {
            session.setIpAddress(ipAddress);
        }
        userSessionRepository.save(session);
    }

    /** Persists a session whose fields the caller has already changed — the organization switch. */
    @SystemTenant("a session is account-level; the organization on it is data, not a tenant scope")
    @Transactional
    public void save(UserSession session) {
        userSessionRepository.save(session);
    }

    /**
     * Every live session for this user, newest activity first, with the caller's own flagged.
     *
     * <p>Expired rows are filtered out in the query rather than shown greyed: a session whose
     * refresh token has expired is not something a person can act on, and listing it would only
     * invite them to revoke something already gone.
     */
    @SystemTenant("lists a user's sessions across every organization they belong to")
    @Transactional(readOnly = true)
    public List<SessionResponse> listSessions(UUID userId, UUID currentSessionId) {
        return userSessionRepository
                .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(userId, Instant.now())
                .stream()
                .map(session -> SessionResponse.builder()
                        .id(session.getId())
                        .client(session.getClient())
                        .userAgent(session.getUserAgent())
                        .ipAddress(session.getIpAddress())
                        .createdAt(session.getCreatedAt())
                        .lastSeenAt(session.getLastSeenAt())
                        .expiresAt(session.getExpiresAt())
                        .current(session.getId().equals(currentSessionId))
                        .build())
                .toList();
    }

    /**
     * Ends one session belonging to this user.
     *
     * <p>Looked up by {@code (id, userId)} so that a session id belonging to somebody else is a
     * 404 and not a revocation. Idempotent: revoking an already-revoked session is a no-op rather
     * than an error, because the two clicks that produce it are the same intent.
     */
    @SystemTenant("acts on the caller's own session, which may be scoped to another organization")
    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        UserSession session = userSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new NotFoundException("Session not found"));

        if (session.getRevokedAt() == null) {
            session.setRevokedAt(Instant.now());
            userSessionRepository.save(session);
        }
        // Unconditional, even when the row was already revoked: the row is the record, the Redis
        // marker is the enforcement, and a marker lost to a Redis restart has to be replaceable.
        tokenBlacklistService.revokeSession(session.getId(), Date.from(session.getExpiresAt()));
        log.info("Revoked session {} for user {}", sessionId, userId);
    }

    /**
     * Signs the account out of everything.
     *
     * <p>The epoch in {@link TokenBlacklistService#revokeAllUserTokens} already does the
     * enforcement half in one Redis write — every token issued before this instant stops
     * authenticating, with no per-session bookkeeping — so this only has to bring the rows into
     * agreement with it.
     */
    @SystemTenant("ends every session the user has, in every organization")
    @Transactional
    public int revokeAllSessions(UUID userId) {
        int revoked = userSessionRepository.revokeAllForUser(userId, Instant.now());
        tokenBlacklistService.revokeAllUserTokens(userId);
        log.info("Signed user {} out everywhere ({} sessions)", userId, revoked);
        return revoked;
    }

    /**
     * Drops rows whose refresh token has expired. Nothing enforcement-related depends on this —
     * an expired session cannot authenticate anything either way — so it is pure housekeeping,
     * and is the reason this table does not grow by one row per login forever.
     */
    @SystemTenant("housekeeping over every user's expired sessions")
    @Scheduled(fixedDelayString = "${auth.session.cleanup-interval-ms:3600000}")
    @Transactional
    public void purgeExpiredSessions() {
        int deleted = userSessionRepository.deleteExpiredBefore(Instant.now());
        if (deleted > 0) {
            log.debug("Purged {} expired user sessions", deleted);
        }
    }
}
