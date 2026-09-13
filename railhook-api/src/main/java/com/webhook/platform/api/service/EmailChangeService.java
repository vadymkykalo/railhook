package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.domain.entity.EmailChangeRequest;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.entity.UserSession;
import com.webhook.platform.api.domain.entity.VerificationEmailSend;
import com.webhook.platform.api.domain.enums.EmailChangeStatus;
import com.webhook.platform.api.domain.repository.EmailChangeRequestRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.ChangeEmailRequest;
import com.webhook.platform.api.dto.EmailChangeResponse;
import com.webhook.platform.api.security.JwtUtil;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Changing the address an account signs in with, without making it a way around what the address
 * stands for.
 *
 * <p>Two shapes, decided by whether the account has proved its current address:
 * <ul>
 *   <li><b>Unverified.</b> The change applies at once and a fresh verification goes to the new
 *       address; the old token stops working. Nothing is granted — the account stays unverified,
 *       and so stays behind the write gate, until the new address is proved. It answers the same
 *       CAPTCHA registration does, because re-typing an address is registering it.</li>
 *   <li><b>Verified.</b> Nothing changes until the new address is proved. The requester re-enters
 *       the password, or — with none, because they sign in with Google — has signed in within the
 *       last {@link #RECENT_SIGN_IN} minutes. A confirmation link goes to the new address and a
 *       notice with a "this wasn't me" link to the old one. Confirming, or cancelling from that
 *       notice, signs every session out, as a password change does.</li>
 * </ul>
 *
 * <p>A taken address is refused with the same answer registration gives, which reveals nothing
 * registration does not. The organization, its plan and its usage belong to the organization
 * and are not touched; a Google link is keyed on Google's subject and survives the change.
 */
@Service
@Slf4j
public class EmailChangeService {

    static final Duration CONFIRMATION_LIFETIME = Duration.ofHours(24);
    static final Duration RECENT_SIGN_IN = Duration.ofMinutes(10);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final EmailChangeRequestRepository changeRepository;
    private final UserSessionService userSessionService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final CaptchaVerifier captchaVerifier;
    private final VerificationMailBudget budget;

    public EmailChangeService(UserRepository userRepository,
                              EmailChangeRequestRepository changeRepository,
                              UserSessionService userSessionService,
                              JwtUtil jwtUtil,
                              BCryptPasswordEncoder passwordEncoder,
                              EmailService emailService,
                              CaptchaVerifier captchaVerifier,
                              VerificationMailBudget budget) {
        this.userRepository = userRepository;
        this.changeRepository = changeRepository;
        this.userSessionService = userSessionService;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.captchaVerifier = captchaVerifier;
        this.budget = budget;
    }

    @SystemTenant("acts on the caller's own account, which is not confined to the organization the request is scoped to")
    @Transactional(readOnly = true)
    public EmailChangeResponse current(UUID userId) {
        User user = requireUser(userId);
        return describe(user, livePending(userId).orElse(null));
    }

    @SystemTenant("acts on the caller's own account, which is not confined to the organization the request is scoped to")
    @Transactional
    public EmailChangeResponse requestChange(UUID userId, ChangeEmailRequest request, String refreshToken,
                                             String clientIp) {
        User user = requireUser(userId);
        String newEmail = request.getNewEmail().trim();
        if (newEmail.equalsIgnoreCase(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is already your email address");
        }

        boolean verified = Boolean.TRUE.equals(user.getEmailVerified());
        if (!verified) {
            if (!captchaVerifier.verify(request.getCaptchaToken(), clientIp)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CAPTCHA verification failed. Please try again.");
            }
        } else if (user.getPasswordHash() != null) {
            if (request.getCurrentPassword() == null
                    || !passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
            }
        } else {
            requireRecentSignIn(userId, refreshToken);
        }

        budget.requireAddressChangeAllowance(user);
        budget.requireSendAllowance(user);
        requireAvailable(newEmail, userId);

        return verified ? startPendingChange(user, newEmail) : applyToUnverified(user, newEmail);
    }

    @SystemTenant("acts on the caller's own account, which is not confined to the organization the request is scoped to")
    @Transactional
    public EmailChangeResponse resend(UUID userId) {
        User user = requireUser(userId);
        EmailChangeRequest pending = livePending(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No email change is waiting for confirmation"));
        budget.requireSendAllowance(user);

        String token = newToken();
        pending.setTokenHash(CryptoUtils.hashApiKey(token));
        pending.setExpiresAt(Instant.now().plus(CONFIRMATION_LIFETIME));
        changeRepository.save(pending);
        budget.recordSend(userId, VerificationEmailSend.EMAIL_CHANGE_RESEND);
        emailService.sendEmailChangeConfirmation(pending.getNewEmail(), token);
        return describe(user, pending);
    }

    @SystemTenant("acts on the caller's own account, which is not confined to the organization the request is scoped to")
    @Transactional
    public void cancel(UUID userId) {
        changeRepository.findByUserIdAndStatus(userId, EmailChangeStatus.PENDING).ifPresent(pending -> {
            settle(pending, EmailChangeStatus.CANCELLED);
            budget.audit(userId, AuditAction.EMAIL_CHANGE_CANCELLED, details(pending, "settings"));
            log.info("Email change for user {} cancelled from settings", userId);
        });
    }

    @SystemTenant("acts on a User by emailed token, with no authenticated caller")
    @Transactional
    public void confirm(String token) {
        EmailChangeRequest pending = changeRepository
                .findByTokenHashAndStatus(CryptoUtils.hashApiKey(token), EmailChangeStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This confirmation link is invalid or has already been used."));
        if (pending.getExpiresAt() == null || pending.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This confirmation link has expired. Ask for the change again.");
        }
        User user = requireUser(pending.getUserId());
        requireAvailable(pending.getNewEmail(), user.getId());

        user.setEmail(pending.getNewEmail());
        saveAddress(user);
        settle(pending, EmailChangeStatus.CONFIRMED);
        userSessionService.revokeAllSessions(user.getId());
        budget.audit(user.getId(), AuditAction.EMAIL_CHANGED, details(pending, "confirmation link"));
        log.info("Email of user {} changed after confirmation; all sessions revoked", user.getId());
    }

    /** "This wasn't me", from the notice sent to the old address. No session needed or trusted. */
    @SystemTenant("acts on a User by emailed token, with no authenticated caller")
    @Transactional
    public void cancelByToken(String cancelToken) {
        EmailChangeRequest pending = changeRepository
                .findByCancelTokenHashAndStatus(CryptoUtils.hashApiKey(cancelToken), EmailChangeStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This link is invalid or the change was already settled."));
        settle(pending, EmailChangeStatus.CANCELLED);
        // Whoever asked held a session. The owner saying it was not them is the reason to end it.
        userSessionService.revokeAllSessions(pending.getUserId());
        budget.audit(pending.getUserId(), AuditAction.EMAIL_CHANGE_CANCELLED, details(pending, "old address"));
        log.warn("Email change for user {} cancelled from the old address; all sessions revoked", pending.getUserId());
    }

    private EmailChangeResponse applyToUnverified(User user, String newEmail) {
        String previous = user.getEmail();
        String token = newToken();
        user.setEmail(newEmail);
        // Replacing the hash is what invalidates the link already sent to the old address.
        user.setVerificationToken(CryptoUtils.hashApiKey(token));
        user.setVerificationTokenExpiresAt(Instant.now().plus(CONFIRMATION_LIFETIME));
        saveAddress(user);

        EmailChangeRequest applied = changeRepository.save(EmailChangeRequest.builder()
                .userId(user.getId())
                .previousEmail(previous)
                .newEmail(newEmail)
                .status(EmailChangeStatus.APPLIED)
                .resolvedAt(Instant.now())
                .build());
        budget.recordSend(user.getId(), VerificationEmailSend.EMAIL_CHANGE);
        emailService.sendVerificationEmail(newEmail, token);
        budget.audit(user.getId(), AuditAction.EMAIL_CHANGED, details(applied, "unverified account"));
        log.info("Unverified user {} moved to a new address; verification sent there", user.getId());
        return describe(user, null).toBuilder().applied(true).build();
    }

    private EmailChangeResponse startPendingChange(User user, String newEmail) {
        changeRepository.findByUserIdAndStatus(user.getId(), EmailChangeStatus.PENDING)
                .ifPresent(previous -> {
                    settle(previous, EmailChangeStatus.CANCELLED);
                    // One pending change per account is a unique index; the replacement below
                    // must not meet the row it replaces.
                    changeRepository.flush();
                });

        String token = newToken();
        String cancelToken = newToken();
        EmailChangeRequest pending = changeRepository.save(EmailChangeRequest.builder()
                .userId(user.getId())
                .previousEmail(user.getEmail())
                .newEmail(newEmail)
                .status(EmailChangeStatus.PENDING)
                .tokenHash(CryptoUtils.hashApiKey(token))
                .cancelTokenHash(CryptoUtils.hashApiKey(cancelToken))
                .expiresAt(Instant.now().plus(CONFIRMATION_LIFETIME))
                .build());
        budget.recordSend(user.getId(), VerificationEmailSend.EMAIL_CHANGE);
        emailService.sendEmailChangeConfirmation(newEmail, token);
        emailService.sendEmailChangeNotice(user.getEmail(), newEmail, cancelToken);
        budget.audit(user.getId(), AuditAction.EMAIL_CHANGE_REQUESTED, details(pending, "settings"));
        log.info("Email change requested for user {}; waiting for the new address to confirm", user.getId());
        return describe(user, pending);
    }

    private void requireRecentSignIn(UUID userId, String refreshToken) {
        Optional<UserSession> session = Optional.ofNullable(refreshToken)
                .filter(jwtUtil::validateToken)
                .flatMap(token -> userSessionService.findByRefreshJti(jwtUtil.getJtiFromToken(token)))
                .filter(s -> userId.equals(s.getUserId()) && s.getRevokedAt() == null);
        if (session.isEmpty() || session.get().getCreatedAt().isBefore(Instant.now().minus(RECENT_SIGN_IN))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Sign in with Google again, then change your email within 10 minutes.");
        }
    }

    /** The same refusal, word for word, that registration gives for an address in use. */
    private void requireAvailable(String email, UUID userId) {
        userRepository.findByEmailIgnoreCase(email)
                .filter(other -> !other.getId().equals(userId))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
                });
    }

    /** Two changes racing to the same address meet the unique constraint; the loser gets the same answer. */
    private void saveAddress(User user) {
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
    }

    private void settle(EmailChangeRequest request, EmailChangeStatus status) {
        request.setStatus(status);
        request.setResolvedAt(Instant.now());
        changeRepository.save(request);
    }

    private Optional<EmailChangeRequest> livePending(UUID userId) {
        return changeRepository.findByUserIdAndStatus(userId, EmailChangeStatus.PENDING)
                .filter(p -> p.getExpiresAt() != null && p.getExpiresAt().isAfter(Instant.now()));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static EmailChangeResponse describe(User user, EmailChangeRequest pending) {
        return EmailChangeResponse.builder()
                .email(user.getEmail())
                .pendingEmail(pending == null ? null : pending.getNewEmail())
                .pendingExpiresAt(pending == null ? null : pending.getExpiresAt())
                .build();
    }

    private static Map<String, Object> details(EmailChangeRequest request, String via) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", request.getPreviousEmail());
        details.put("to", request.getNewEmail());
        details.put("via", via);
        if (request.getStatus() == EmailChangeStatus.PENDING && request.getExpiresAt() != null) {
            details.put("expiresAt", request.getExpiresAt().toString());
        }
        return details;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
