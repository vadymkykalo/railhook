package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.EmailAddresses;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.entity.UserSession;
import com.webhook.platform.api.domain.entity.VerificationEmailSend;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.*;
import com.webhook.platform.api.security.JwtUtil;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;
    private final PlanRepository planRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserSessionService userSessionService;
    private final AccountLockoutService accountLockoutService;
    private final EmailService emailService;
    private final VerificationMailBudget verificationMailBudget;
    private final OnboardingMailService onboardingMailService;
    private final boolean billingEnabled;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_EXPIRY_HOURS = 24;

    public AuthService(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            MembershipRepository membershipRepository,
            PlanRepository planRepository,
            JwtUtil jwtUtil,
            BCryptPasswordEncoder passwordEncoder,
            TokenBlacklistService tokenBlacklistService,
            UserSessionService userSessionService,
            AccountLockoutService accountLockoutService,
            EmailService emailService,
            VerificationMailBudget verificationMailBudget,
            OnboardingMailService onboardingMailService,
            @Value("${billing.enabled:false}") boolean billingEnabled) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.planRepository = planRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userSessionService = userSessionService;
        this.accountLockoutService = accountLockoutService;
        this.emailService = emailService;
        this.verificationMailBudget = verificationMailBudget;
        this.onboardingMailService = onboardingMailService;
        this.billingEnabled = billingEnabled;
    }

    @SystemTenant("creates the Organization it then belongs to, so there is no tenant to run in yet; the Membership it inserts sets organizationId explicitly")
    @Auditable(action = AuditAction.REGISTER, resourceType = "Auth")
    @Transactional
    public AuthResponse register(RegisterRequest request, SessionOrigin origin) {
        String email = EmailAddresses.normalize(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        // Without email a token could never arrive, and VerificationGate would block every write.
        boolean verificationIsDeliverable = emailService.isEnabled();

        String verificationToken = verificationIsDeliverable ? generateVerificationToken() : null;

        User user = User.builder()
                .email(email)
                .fullName(request.getFullName())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .status(verificationIsDeliverable ? UserStatus.PENDING_VERIFICATION : UserStatus.ACTIVE)
                .emailVerified(!verificationIsDeliverable)
                .verificationToken(verificationIsDeliverable ? CryptoUtils.hashApiKey(verificationToken) : null)
                .verificationTokenExpiresAt(verificationIsDeliverable
                        ? Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS)
                        : null)
                .build();
        user = userRepository.save(user);

        Organization organization = createOrganizationOwnedBy(user, request.getOrganizationName());

        if (verificationIsDeliverable) {
            verificationMailBudget.recordSend(user.getId(), VerificationEmailSend.REGISTER);
            emailService.sendVerificationEmail(user.getEmail(), verificationToken);
        } else {
            onboardingMailService.welcome(user);
        }

        return issueSession(user, organization.getId(), MembershipRole.OWNER, origin,
                !verificationIsDeliverable);
    }

    @SystemTenant("reads memberships to find which organization to issue a token for -- the answer is what a tenant scope would need as input")
    @Auditable(action = AuditAction.LOGIN, resourceType = "Auth")
    public AuthResponse login(LoginRequest request, SessionOrigin origin) {
        User user = userRepository.findByEmail(EmailAddresses.normalize(request.getEmail()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        // Checked before the password so a locked account does not cost a BCrypt hash per attempt.
        Duration lockedFor = accountLockoutService.remainingLockout(user);
        if (!lockedFor.isZero()) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "Too many failed sign-in attempts. Try again in "
                            + Math.max(1, lockedFor.toMinutes() + (lockedFor.toSecondsPart() > 0 ? 1 : 0))
                            + " minute(s), or reset your password to unlock the account now.");
        }

        // No password (Google account) fails like a wrong one, so sign-in method is not revealed.
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            accountLockoutService.recordFailure(user);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is disabled");
        }

        accountLockoutService.clearFailures(user);

        Membership membership = membershipToSignInWith(user);

        return issueSession(user, membership.getOrganizationId(), membership.getRole(), origin,
                Boolean.TRUE.equals(user.getEmailVerified()));
    }

    /** Runs inside the caller's system scope and transaction. */
    public Organization createOrganizationOwnedBy(User owner, String organizationName) {
        String defaultPlanName = billingEnabled ? "free" : "self_hosted";
        Plan defaultPlan = planRepository.findByName(defaultPlanName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Default plan '" + defaultPlanName + "' not found. Run database migrations."));

        Organization organization = organizationRepository.save(Organization.builder()
                .name(organizationName)
                .plan(defaultPlan)
                .build());

        membershipRepository.save(Membership.builder()
                .userId(owner.getId())
                .organizationId(organization.getId())
                .role(MembershipRole.OWNER)
                .build());
        return organization;
    }

    public AuthResponse issueSessionFor(User user, SessionOrigin origin) {
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is disabled");
        }
        Membership membership = membershipToSignInWith(user);
        return issueSession(user, membership.getOrganizationId(), membership.getRole(), origin,
                Boolean.TRUE.equals(user.getEmailVerified()));
    }

    // The session id is generated here because the tokens carry it as the sid claim.
    private AuthResponse issueSession(User user, UUID organizationId, MembershipRole role,
                                      SessionOrigin origin, boolean emailVerified) {
        UUID sessionId = UUID.randomUUID();
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), sessionId);

        userSessionService.open(UserSession.builder()
                .id(sessionId)
                .userId(user.getId())
                .organizationId(organizationId)
                .refreshTokenJti(jwtUtil.getJtiFromToken(refreshToken))
                .client(origin.client())
                .userAgent(origin.userAgent())
                .ipAddress(origin.ipAddress())
                .lastSeenAt(Instant.now())
                .expiresAt(jwtUtil.getExpirationFromToken(refreshToken).toInstant())
                .build());

        return AuthResponse.builder()
                .accessToken(jwtUtil.generateAccessToken(user.getId(), organizationId, role, sessionId, emailVerified))
                .refreshToken(refreshToken)
                .emailVerified(emailVerified)
                .build();
    }

    // The role comes from the target membership, never the old token, so OWNER does not carry across.
    @SystemTenant("re-scopes a session from one organization to another, so it is in neither while it decides")
    public AuthResponse switchOrganization(UUID userId, SwitchOrganizationRequest request, String refreshToken) {
        UserSession session = requireOwnLiveSession(userId, refreshToken);

        Membership membership = membershipRepository
                .findByUserIdAndOrganizationId(userId, request.getOrganizationId())
                // Refused like a non-member, so a suspension is neither bypassed nor revealed.
                .filter(m -> m.getStatus() != MembershipStatus.DISABLED)
                .orElseThrow(() -> new ForbiddenException("You are not a member of that organization"));

        session.setOrganizationId(membership.getOrganizationId());
        session.setLastSeenAt(Instant.now());
        userSessionService.save(session);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        log.info("User {} switched session {} to organization {}",
                userId, session.getId(), membership.getOrganizationId());

        return AuthResponse.builder()
                .accessToken(jwtUtil.generateAccessToken(
                        userId, membership.getOrganizationId(), membership.getRole(), session.getId(),
                        Boolean.TRUE.equals(user.getEmailVerified())))
                .emailVerified(Boolean.TRUE.equals(user.getEmailVerified()))
                .build();
    }

    // Looked up by jti, not sid: the jti rotates on every refresh, so a replayed token finds nothing.
    private UserSession requireOwnLiveSession(UUID userId, String refreshToken) {
        if (refreshToken == null || !jwtUtil.validateToken(refreshToken)
                || !JwtUtil.TOKEN_TYPE_REFRESH.equals(jwtUtil.getTokenType(refreshToken))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token missing or invalid");
        }
        UserSession session = userSessionService.findByRefreshJti(jwtUtil.getJtiFromToken(refreshToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session not found"));

        if (!session.getUserId().equals(userId) || !session.isActive(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is no longer valid");
        }
        return session;
    }

    @SystemTenant("same as login: the membership read decides the organization the new token names")
    public AuthResponse refreshToken(String refreshToken, SessionOrigin origin) {
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }

        // Access tokens and legacy tokens without a typ claim are refused.
        if (!JwtUtil.TOKEN_TYPE_REFRESH.equals(jwtUtil.getTokenType(refreshToken))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }

        String oldJti = jwtUtil.getJtiFromToken(refreshToken);
        UUID userId = jwtUtil.getUserIdFromToken(refreshToken);

        if (tokenBlacklistService.isBlacklisted(oldJti)) {
            // A replayed consumed token means theft: revoke every session, not just this token.
            userSessionService.revokeAllSessions(userId);
            log.warn("Rejected reuse of already-rotated/revoked refresh token for user {}; revoked all sessions", userId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token has been revoked");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is disabled");
        }

        UserSession session = userSessionService.findByRefreshJti(oldJti).orElse(null);

        if (session == null && jwtUtil.getSessionIdFromToken(refreshToken) != null) {
            // Rotated away or signed out; the session row decides, since Redis may have lost the blacklist.
            log.warn("Refresh token names session {} but is not its current token; refusing",
                    jwtUtil.getSessionIdFromToken(refreshToken));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is no longer valid");
        }
        if (session != null && !session.isActive(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out");
        }

        Membership membership = membershipForRefresh(user, session);

        tokenBlacklistService.blacklist(oldJti, jwtUtil.getExpirationFromToken(refreshToken));

        if (session == null) {
            // Minted before sessions existed. Give it one rather than sign everybody out on upgrade.
            return issueSession(user, membership.getOrganizationId(), membership.getRole(), origin,
                    Boolean.TRUE.equals(user.getEmailVerified()));
        }

        String newRefreshToken = jwtUtil.generateRefreshToken(user.getId(), session.getId());
        userSessionService.rotate(session, jwtUtil.getJtiFromToken(newRefreshToken),
                jwtUtil.getExpirationFromToken(newRefreshToken).toInstant(), origin.ipAddress());

        return AuthResponse.builder()
                .accessToken(jwtUtil.generateAccessToken(
                        user.getId(), membership.getOrganizationId(), membership.getRole(), session.getId(),
                        Boolean.TRUE.equals(user.getEmailVerified())))
                .refreshToken(newRefreshToken)
                .emailVerified(Boolean.TRUE.equals(user.getEmailVerified()))
                .build();
    }

    // Keeps the switched-to organization; if that membership is gone, fall back rather than fail.
    private Membership membershipForRefresh(User user, UserSession session) {
        if (session != null) {
            Optional<Membership> remembered = membershipRepository
                    .findByUserIdAndOrganizationId(user.getId(), session.getOrganizationId())
                    // Otherwise a suspended member keeps refreshing indefinitely.
                    .filter(m -> m.getStatus() != MembershipStatus.DISABLED);
            if (remembered.isPresent()) {
                return remembered.get();
            }
            log.info("Session {} named organization {}, which user {} can no longer be issued a "
                            + "token for; falling back to their oldest active membership",
                    session.getId(), session.getOrganizationId(), user.getId());
        }

        Membership oldest = membershipToIssueTokenFor(user.getId());
        if (session != null) {
            session.setOrganizationId(oldest.getOrganizationId());
        }
        return oldest;
    }

    // An account left in no organization gets one; otherwise it could never sign in again.
    private Membership membershipToSignInWith(User user) {
        if (membershipRepository.findByUserIdOrderByCreatedAtAsc(user.getId()).isEmpty()) {
            Organization organization = createOrganizationOwnedBy(user, workspaceNameFor(user));
            log.info("User {} signed in with no organization left; created {} for them",
                    user.getId(), organization.getId());
        }
        return membershipToIssueTokenFor(user.getId());
    }

    private static String workspaceNameFor(User user) {
        String person = user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName().trim()
                : user.getEmail().substring(0, Math.max(1, user.getEmail().indexOf('@')));
        String name = person + "'s workspace";
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    // Ordered, or a user in two organizations got a different tenant per login. INVITED is not
    // skipped: an invitee signs in with it to accept.
    private Membership membershipToIssueTokenFor(UUID userId) {
        List<Membership> memberships = membershipRepository.findByUserIdOrderByCreatedAtAsc(userId);
        return memberships.stream()
                .filter(m -> m.getStatus() != MembershipStatus.DISABLED)
                .findFirst()
                .orElseThrow(() -> memberships.isEmpty()
                        ? new ResponseStatusException(HttpStatus.NOT_FOUND, "No organization membership found")
                        : new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "Your membership in this organization has been suspended"));
    }

    @Auditable(action = AuditAction.LOGOUT, resourceType = "Auth")
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && jwtUtil.validateToken(accessToken)) {
            tokenBlacklistService.blacklist(
                    jwtUtil.getJtiFromToken(accessToken),
                    jwtUtil.getExpirationFromToken(accessToken));
        }
        if (refreshToken != null && jwtUtil.validateToken(refreshToken)) {
            tokenBlacklistService.blacklist(
                    jwtUtil.getJtiFromToken(refreshToken),
                    jwtUtil.getExpirationFromToken(refreshToken));
            userSessionService.findByRefreshJti(jwtUtil.getJtiFromToken(refreshToken))
                    .ifPresent(session -> userSessionService.revokeSession(session.getUserId(), session.getId()));
        }
    }

    public List<SessionResponse> listSessions(UUID userId, String refreshToken) {
        UUID currentSessionId = null;
        if (refreshToken != null && jwtUtil.validateToken(refreshToken)) {
            currentSessionId = userSessionService.findByRefreshJti(jwtUtil.getJtiFromToken(refreshToken))
                    .map(UserSession::getId)
                    .orElse(null);
        }
        return userSessionService.listSessions(userId, currentSessionId);
    }

    @SystemTenant("acts on a User by emailed token, before any organization is established")
    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByVerificationToken(CryptoUtils.hashApiKey(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification token"));

        if (user.getVerificationTokenExpiresAt() != null
                && user.getVerificationTokenExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification token has expired. Please request a new one.");
        }

        user.setEmailVerified(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiresAt(null);
        userRepository.save(user);
        onboardingMailService.welcome(user);
        log.info("Email verified for user {}", user.getEmail());
    }

    @SystemTenant("acts on a User by email address, with no authenticated caller")
    @Transactional
    public void resendVerification(String email) {
        User user = userRepository.findByEmail(EmailAddresses.normalize(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is already verified");
        }
        // Daily budget, shared with email change so neither bypasses the other.
        verificationMailBudget.requireSendAllowance(user);

        String newToken = generateVerificationToken();
        user.setVerificationToken(CryptoUtils.hashApiKey(newToken));
        user.setVerificationTokenExpiresAt(Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS));
        userRepository.save(user);

        verificationMailBudget.recordSend(user.getId(), VerificationEmailSend.RESEND);
        emailService.sendVerificationEmail(user.getEmail(), newToken);
        log.info("Resent verification email to {}", user.getEmail());
    }

    private String generateVerificationToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Auditable(action = AuditAction.PASSWORD_CHANGED, resourceType = "Auth")
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (user.getPasswordHash() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This account has no password yet. Use \"Forgot password\" to set one.");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        if (currentPassword.equals(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different from current password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockoutExpiresAt(null);
        userRepository.save(user);
        // Access tokens are not re-checked against the database, so old sessions would outlive the change.
        userSessionService.revokeAllSessions(userId);
        log.info("Password changed for user {}, all sessions revoked", userId);
    }

    @SystemTenant("acts on a User by email address, with no authenticated caller")
    @Auditable(action = AuditAction.PASSWORD_RESET_REQUESTED, resourceType = "Auth")
    @Transactional
    public void forgotPassword(String email) {
        User user = userRepository.findByEmail(EmailAddresses.normalize(email)).orElse(null);

        // Always succeeds, to prevent email enumeration.
        if (user == null) {
            log.info("Password reset requested for non-existent email: {}", email);
            return;
        }

        String resetToken = generateVerificationToken();
        user.setPasswordResetToken(CryptoUtils.hashApiKey(resetToken));
        user.setPasswordResetTokenExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        userRepository.save(user);

        emailService.sendPasswordResetEmail(user.getEmail(), resetToken);
        log.info("Password reset token generated for user {}", user.getEmail());
    }

    @SystemTenant("acts on a User by emailed token, with no authenticated caller")
    @Auditable(action = AuditAction.PASSWORD_RESET, resourceType = "Auth")
    @Transactional
    public void resetPassword(String token, String newPassword) {
        User user = userRepository.findByPasswordResetToken(CryptoUtils.hashApiKey(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token"));

        if (user.getPasswordResetTokenExpiresAt() != null
                && user.getPasswordResetTokenExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset token has expired. Please request a new one.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiresAt(null);
        // The unlock path. It is why lockout is not a denial of service against a known address.
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockoutExpiresAt(null);
        userRepository.save(user);
        // Reset is how a taken-over account is recovered, so the attacker's tokens must die now.
        userSessionService.revokeAllSessions(user.getId());
        log.info("Password reset completed for user {}, all sessions revoked", user.getEmail());
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (request.getFullName() != null) {
            user.setFullName(request.getFullName().isBlank() ? null : request.getFullName().trim());
        }

        user = userRepository.save(user);
        log.info("Profile updated for user {}", userId);

        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .status(user.getStatus())
                .build();
    }

    public CurrentUserResponse getCurrentUser(UUID userId, MembershipRole role) {
        UUID organizationId = TenantContext.require();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));

        UserResponse userResponse = UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .status(user.getStatus())
                .build();

        OrganizationResponse orgResponse = OrganizationResponse.builder()
                .id(organization.getId())
                .name(organization.getName())
                .createdAt(organization.getCreatedAt())
                .build();

        return CurrentUserResponse.builder()
                .user(userResponse)
                .organization(orgResponse)
                .role(role)
                .emailDeliveryEnabled(emailService.isEnabled())
                .hasPassword(user.getPasswordHash() != null)
                .build();
    }
}
