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
 * Being listed in PLATFORM_ADMIN_EMAILS is not enough: the address must be verified, the account
 * active and the sign-in recent. Read from the database, not token claims.
 */
@Service
@RequiredArgsConstructor
public class PlatformAdminAccessService {

    public static final Duration MAX_SIGN_IN_AGE = Duration.ofHours(12);

    public enum Outcome {
        GRANTED,
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
        // A token minted before sessions existed cannot prove when its sign-in was.
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

    @SystemTenant("an account's standing on the deployment belongs to no organization")
    @Transactional(readOnly = true)
    public boolean offersPanelTo(UUID userId) {
        return !emails.isEmpty() && userId != null
                && userRepository.findById(userId).map(this::isPlatformAdmin).orElse(false);
    }

    // Without the sign-in age: only decides whether to offer the panel.
    public boolean isPlatformAdmin(User user) {
        return user != null
                && emails.isListed(user.getEmail())
                && Boolean.TRUE.equals(user.getEmailVerified())
                && user.getStatus() == UserStatus.ACTIVE;
    }
}
