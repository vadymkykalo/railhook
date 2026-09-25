package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.EmailAddresses;
import com.webhook.platform.api.domain.entity.SignInHandoff;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.entity.UserIdentity;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.SignInHandoffRepository;
import com.webhook.platform.api.domain.repository.UserIdentityRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.service.signin.SignInFailure;
import com.webhook.platform.api.service.signin.SignInRejectedException;
import com.webhook.platform.api.service.signin.VerifiedIdentity;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class ExternalSignInService {

    static final Duration HANDOFF_LIFETIME = Duration.ofSeconds(60);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final SignInHandoffRepository signInHandoffRepository;
    private final UserSessionService userSessionService;
    private final AuthService authService;
    private final ProjectService projectService;
    private final OnboardingMailService onboardingMailService;

    public ExternalSignInService(UserRepository userRepository,
                                 UserIdentityRepository userIdentityRepository,
                                 SignInHandoffRepository signInHandoffRepository,
                                 UserSessionService userSessionService,
                                 AuthService authService,
                                 ProjectService projectService,
                                 OnboardingMailService onboardingMailService) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.signInHandoffRepository = signInHandoffRepository;
        this.userSessionService = userSessionService;
        this.authService = authService;
        this.projectService = projectService;
        this.onboardingMailService = onboardingMailService;
    }

    // Matched on the provider subject first, then the address. An unverified account's password
    // is removed, since someone else may have registered it.
    @SystemTenant("matches a person an identity provider vouched for to an account, before any organization is chosen")
    @Transactional
    public Optional<UUID> findOrLinkVerifiedIdentity(VerifiedIdentity identity) {
        Optional<UserIdentity> linked = userIdentityRepository
                .findByProviderAndSubject(identity.provider(), identity.subject());
        if (linked.isPresent()) {
            User user = userRepository.findById(linked.get().getUserId())
                    .orElseThrow(() -> new SignInRejectedException(SignInFailure.IDENTITY,
                            "a linked identity names a user that no longer exists"));
            requireNotDisabled(user);
            return Optional.of(user.getId());
        }

        Optional<User> byAddress = userRepository.findByEmail(EmailAddresses.normalize(identity.email()));
        if (byAddress.isEmpty()) {
            return Optional.empty();
        }

        User user = byAddress.get();
        requireNotDisabled(user);
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            user.setPasswordHash(null);
            user.setEmailVerified(true);
            if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
                user.setStatus(UserStatus.ACTIVE);
            }
            user.setVerificationToken(null);
            user.setVerificationTokenExpiresAt(null);
            userRepository.save(user);
            onboardingMailService.welcome(user);
            userSessionService.revokeAllSessions(user.getId());
            log.info("Address of user {} verified by {}; the unverified password was removed", user.getId(),
                    identity.provider());
        }
        link(user.getId(), identity);
        return Optional.of(user.getId());
    }

    // No CAPTCHA: a verified Google account is not free to invent, unlike an address.
    @SystemTenant("creates the Organization it then belongs to, so there is no tenant to run in yet; the Membership it inserts sets organizationId explicitly")
    @Auditable(action = AuditAction.REGISTER, resourceType = "Auth")
    @Transactional
    public UUID registerWithVerifiedIdentity(VerifiedIdentity identity, String organizationName) {
        User user = userRepository.save(User.builder()
                .email(identity.email())
                .fullName(identity.fullName() == null || identity.fullName().isBlank() ? null : identity.fullName().trim())
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build());
        // Nothing was asked but a click, so the dashboard opens with a project to work in.
        projectService.createFirstProject(authService.createOrganizationOwnedBy(user, organizationName));
        link(user.getId(), identity);
        onboardingMailService.welcome(user);
        log.info("Created account {} through {}", user.getId(), identity.provider());
        return user.getId();
    }

    // Only the hash is stored. Expired rows are swept here since this is the only writer.
    @SystemTenant("records a finished sign-in for a user who has no session yet")
    @Transactional
    public String issueSignInHandoff(UUID userId, boolean accountCreated) {
        Instant now = Instant.now();
        signInHandoffRepository.deleteExpiredBefore(now.minus(Duration.ofHours(1)));

        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        signInHandoffRepository.save(SignInHandoff.builder()
                .codeHash(CryptoUtils.hashApiKey(code))
                .userId(userId)
                .accountCreated(accountCreated)
                .expiresAt(now.plus(HANDOFF_LIFETIME))
                .build());
        return code;
    }

    // Binds the code to the browser that finished sign-in, so a forwarded code opens nothing.
    public static String browserBindingFor(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static Duration handoffLifetime() {
        return HANDOFF_LIFETIME;
    }

    // Checked before the code is consumed, so a refused attempt does not spend the owner's sign-in.
    @SystemTenant("same as login: the membership read decides the organization the new token names")
    @Auditable(action = AuditAction.LOGIN, resourceType = "Auth")
    @Transactional
    public AuthResponse exchangeSignInHandoff(String code, String browserBinding, SessionOrigin origin) {
        if (browserBinding == null || !MessageDigest.isEqual(
                browserBinding.getBytes(StandardCharsets.UTF_8),
                browserBindingFor(code).getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "This sign-in link was opened in a different browser. Sign in again.");
        }
        String codeHash = CryptoUtils.hashApiKey(code);
        if (signInHandoffRepository.consume(codeHash, Instant.now()) == 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "This sign-in link has already been used or has expired. Sign in again.");
        }
        SignInHandoff handoff = signInHandoffRepository.findById(codeHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again."));
        User user = userRepository.findById(handoff.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again."));
        return authService.issueSessionFor(user, origin);
    }

    private void link(UUID userId, VerifiedIdentity identity) {
        userIdentityRepository.save(UserIdentity.builder()
                .userId(userId)
                .provider(identity.provider())
                .subject(identity.subject())
                .email(identity.email())
                .build());
    }

    private static void requireNotDisabled(User user) {
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new SignInRejectedException(SignInFailure.ACCOUNT_DISABLED, "user " + user.getId() + " is disabled");
        }
    }
}
