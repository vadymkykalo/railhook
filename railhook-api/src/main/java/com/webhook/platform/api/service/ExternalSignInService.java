package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
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

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Accounts reached through an identity provider rather than a password: finding the account a
 * verified identity belongs to, creating one when there is none, and handing the finished sign-in
 * to the dashboard. The protocol with Google itself is {@code GoogleSignInService}; nothing here
 * knows about redirects or tokens from outside.
 */
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

    public ExternalSignInService(UserRepository userRepository,
                                 UserIdentityRepository userIdentityRepository,
                                 SignInHandoffRepository signInHandoffRepository,
                                 UserSessionService userSessionService,
                                 AuthService authService) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.signInHandoffRepository = signInHandoffRepository;
        this.userSessionService = userSessionService;
        this.authService = authService;
    }

    /**
     * The account a verified identity belongs to, linking it on first use; empty when there is none.
     *
     * <p>Matched on the provider's subject first, so a person who changes the address on their
     * Google account still arrives in the same account. Failing that, on the address, which the
     * provider has verified — so an account that signed up with a password gains Google as a
     * second way in rather than a duplicate account appearing beside it.
     *
     * <p>One case is not a plain link. An account whose address was never verified may have been
     * registered by someone who is not its owner, with a password of their own, waiting for the
     * owner to arrive. When the owner proves the address through Google, that password — which
     * nobody ever proved belonged to them — is removed and its sessions end.
     */
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

        Optional<User> byAddress = userRepository.findByEmailIgnoreCase(identity.email());
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
            userSessionService.revokeAllSessions(user.getId());
            log.info("Address of user {} verified by {}; the unverified password was removed", user.getId(),
                    identity.provider());
        }
        link(user.getId(), identity);
        return Optional.of(user.getId());
    }

    /**
     * A new account for a person nobody has seen before: verified by the provider, no password,
     * and an organization they own on the plan a registered account gets.
     *
     * <p>No CAPTCHA here, deliberately. The challenge exists because an address is free to invent;
     * a Google account the provider has verified is not, and Google runs its own abuse checks
     * before it vouches for one. ProductionSafetyValidator's hosted-mode rules still hold: the
     * password registration path keeps its challenge.
     */
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
        authService.createOrganizationOwnedBy(user, organizationName);
        link(user.getId(), identity);
        log.info("Created account {} through {}", user.getId(), identity.provider());
        return user.getId();
    }

    /**
     * A one-time code for the dashboard to trade for a session. Only its hash is stored.
     * Expired rows are swept here rather than on a schedule: they are only ever written on this path.
     */
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

    @SystemTenant("same as login: the membership read decides the organization the new token names")
    @Auditable(action = AuditAction.LOGIN, resourceType = "Auth")
    @Transactional
    public AuthResponse exchangeSignInHandoff(String code, SessionOrigin origin) {
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
