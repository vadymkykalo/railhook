package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.entity.UserSession;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.domain.repository.UserSessionRepository;
import com.webhook.platform.api.security.PlatformAdminEmails;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Whether a signed-in person is the platform admin, decided afresh on every admin request.
 *
 * <p>Four conditions, each closing a way in that a listed address alone would leave open:
 * <ul>
 *   <li><b>listed</b> in {@code PLATFORM_ADMIN_EMAILS};</li>
 *   <li><b>verified</b> — on a deployment that does not send mail, registration marks an address
 *       verified without proof, but a deployment that does send mail would otherwise let anyone
 *       register the operator's address and wait;</li>
 *   <li><b>active</b> — a disabled account keeps its unexpired tokens for up to a quarter of an
 *       hour, and that quarter of an hour must not include every tenant's data;</li>
 *   <li><b>a recent sign-in</b> — the session behind the token started within
 *       {@link #MAX_SIGN_IN_AGE}. Refresh keeps a session alive for weeks, which is right for a
 *       dashboard and wrong for the one screen that reaches across every organization.</li>
 * </ul>
 *
 * <p>Read from the database rather than the token's claims: a claim is what was true when the
 * token was minted, and these are exactly the facts an incident changes.
 */
@Service
@RequiredArgsConstructor
public class PlatformAdminAccessService {

    public static final Duration MAX_SIGN_IN_AGE = Duration.ofHours(12);

    public enum Outcome {
        GRANTED,
        /** Everything holds except the sign-in is too old; signing in again fixes it. */
        REAUTHENTICATE,
        DENIED
    }

    public record Decision(Outcome outcome, String email) {
        static Decision denied() {
            return new Decision(Outcome.DENIED, null);
        }
    }

    private final PlatformAdminEmails emails;
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final Clock clock;

    /** Whether the panel exists on this deployment at all. */
    public boolean isConfigured() {
        return !emails.isEmpty();
    }

    @SystemTenant("deciding whether a caller is the deployment's operator reads their account and session, "
            + "which belong to no organization")
    @Transactional(readOnly = true)
    public Decision evaluate(UUID userId, UUID sessionId) {
        if (emails.isEmpty() || userId == null) {
            return Decision.denied();
        }
        User user = userRepository.findById(userId).orElse(null);
        if (!isPlatformAdmin(user)) {
            return Decision.denied();
        }
        // A token minted before sessions existed names none, and cannot prove when its sign-in was.
        if (sessionId == null) {
            return new Decision(Outcome.REAUTHENTICATE, user.getEmail());
        }
        UserSession session = userSessionRepository.findByIdAndUserId(sessionId, userId).orElse(null);
        Instant now = Instant.now(clock);
        if (session == null || session.getRevokedAt() != null || session.getExpiresAt().isBefore(now)) {
            return Decision.denied();
        }
        if (session.getCreatedAt() == null || session.getCreatedAt().isBefore(now.minus(MAX_SIGN_IN_AGE))) {
            return new Decision(Outcome.REAUTHENTICATE, user.getEmail());
        }
        return new Decision(Outcome.GRANTED, user.getEmail());
    }

    /** Whether the dashboard should offer this account the panel — see {@link #isPlatformAdmin(User)}. */
    @SystemTenant("an account's standing on the deployment belongs to no organization")
    @Transactional(readOnly = true)
    public boolean offersPanelTo(UUID userId) {
        return !emails.isEmpty() && userId != null
                && userRepository.findById(userId).map(this::isPlatformAdmin).orElse(false);
    }

    /**
     * The account-level half of the decision, without the sign-in age — what the dashboard uses
     * to decide whether to offer the panel. The panel itself still asks {@link #evaluate}.
     */
    public boolean isPlatformAdmin(User user) {
        return user != null
                && emails.isListed(user.getEmail())
                && Boolean.TRUE.equals(user.getEmailVerified())
                && user.getStatus() == UserStatus.ACTIVE;
    }
}
