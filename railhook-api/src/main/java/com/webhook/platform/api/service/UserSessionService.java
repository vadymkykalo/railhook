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
 * Rows are the list, Redis is the enforcement. UserSession has no @TenantId, so every lookup is by
 * (session id, user id), which is the whole ownership check.
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

    /** Takes the built entity so the organization arrives as data, not as an organizationId parameter. */
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

    /** Must happen together with blacklisting the old jti, or the session can never be refreshed again. */
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

    @SystemTenant("a session is account-level; the organization on it is data, not a tenant scope")
    @Transactional
    public void save(UserSession session) {
        userSessionRepository.save(session);
    }

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

    /** Looked up by (id, userId), so someone else's session id is a 404. Revoking twice is a no-op. */
    @SystemTenant("acts on the caller's own session, which may be scoped to another organization")
    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        UserSession session = userSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new NotFoundException("Session not found"));

        if (session.getRevokedAt() == null) {
            session.setRevokedAt(Instant.now());
            userSessionRepository.save(session);
        }
        // Unconditional: a Redis marker lost to a restart has to be replaceable.
        tokenBlacklistService.revokeSession(session.getId(), Date.from(session.getExpiresAt()));
        log.info("Revoked session {} for user {}", sessionId, userId);
    }

    /** The epoch marker already does the enforcement in one Redis write; this updates the rows. */
    @SystemTenant("ends every session the user has, in every organization")
    @Transactional
    public int revokeAllSessions(UUID userId) {
        int revoked = userSessionRepository.revokeAllForUser(userId, Instant.now());
        tokenBlacklistService.revokeAllUserTokens(userId);
        log.info("Signed user {} out everywhere ({} sessions)", userId, revoked);
        return revoked;
    }

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
