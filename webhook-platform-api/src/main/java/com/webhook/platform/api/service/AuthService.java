package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.entity.UserSession;
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
            @Value("${billing.enabled:false}") boolean billingEnabled) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.planRepository = planRepository;
        this.jwtUtil = jwtUtil;
        // Injected rather than constructed, so the BCrypt work factor is one configured number
        // for the whole application instead of the library's 2010 default in two places --
        // see PasswordEncoderConfig.
        this.passwordEncoder = passwordEncoder;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userSessionService = userSessionService;
        this.accountLockoutService = accountLockoutService;
        this.emailService = emailService;
        this.billingEnabled = billingEnabled;
    }

    @SystemTenant("creates the Organization it then belongs to, so there is no tenant to run in yet; the Membership it inserts sets organizationId explicitly")
    @Auditable(action = AuditAction.REGISTER, resourceType = "Auth")
    @Transactional
    public AuthResponse register(RegisterRequest request, SessionOrigin origin) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        // With email disabled there is no channel that can carry a verification
        // token to this address, so requiring verification is a gate with no key:
        // VerificationGate disables every write in the dashboard, and the only way
        // through is to read the API container's logs. Skip it — an unsent email
        // proves nothing about an address, so nothing is being given up here.
        boolean verificationIsDeliverable = emailService.isEnabled();

        // Only the hash is persisted — the plaintext token exists solely
        // to be emailed to the user and is never written to the database or logs.
        String verificationToken = verificationIsDeliverable ? generateVerificationToken() : null;

        User user = User.builder()
                .email(request.getEmail())
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

        String defaultPlanName = billingEnabled ? "free" : "self_hosted";
        Plan defaultPlan = planRepository.findByName(defaultPlanName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Default plan '" + defaultPlanName + "' not found. Run database migrations."));

        Organization organization = Organization.builder()
                .name(request.getOrganizationName())
                .plan(defaultPlan)
                .build();
        organization = organizationRepository.save(organization);

        Membership membership = Membership.builder()
                .userId(user.getId())
                .organizationId(organization.getId())
                .role(MembershipRole.OWNER)
                .build();
        membershipRepository.save(membership);

        if (verificationIsDeliverable) {
            emailService.sendVerificationEmail(user.getEmail(), verificationToken);
        }

        return issueSession(user, organization.getId(), MembershipRole.OWNER, origin,
                !verificationIsDeliverable);
    }

    @SystemTenant("reads memberships to find which organization to issue a token for -- the answer is what a tenant scope would need as input")
    @Auditable(action = AuditAction.LOGIN, resourceType = "Auth")
    public AuthResponse login(LoginRequest request, SessionOrigin origin) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        // Before the password check, not after. Verifying first would still spend a BCrypt hash
        // -- deliberately expensive -- on every attempt of an attack the lockout exists to stop,
        // which turns the lockout into a way to make the server do the work instead.
        Duration lockedFor = accountLockoutService.remainingLockout(user);
        if (!lockedFor.isZero()) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "Too many failed sign-in attempts. Try again in "
                            + Math.max(1, lockedFor.toMinutes() + (lockedFor.toSecondsPart() > 0 ? 1 : 0))
                            + " minute(s), or reset your password to unlock the account now.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            accountLockoutService.recordFailure(user);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is disabled");
        }

        // The password was right, so whatever was counted against this account was not an
        // attack in progress. An account in daily use therefore never accumulates a lockout.
        accountLockoutService.clearFailures(user);

        Membership membership = membershipToIssueTokenFor(user.getId());

        return issueSession(user, membership.getOrganizationId(), membership.getRole(), origin,
                Boolean.TRUE.equals(user.getEmailVerified()));
    }

    /**
     * Mints a token pair and the {@code user_sessions} row that ties them together.
     *
     * <p>The session id is chosen here rather than by the database because it has to be inside
     * the tokens: it is the {@code sid} claim that lets one session be signed out without
     * touching the others.
     */
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

    /**
     * Re-scopes the caller's current session to another organization they belong to.
     *
     * <p>The two things this must not do, which is most of what it is:
     *
     * <ul>
     *   <li><b>Mint for an organization the caller is not in.</b> The target arrives as caller
     *       input — the only endpoint where that is the point rather than a smell — so nothing is
     *       issued until a {@code Membership} joining this user to this organization has been
     *       found, and the role on the new token comes from <em>that row</em>, never from the
     *       token being replaced. A user who is OWNER of one organization and VIEWER of another
     *       must not carry OWNER across.</li>
     *   <li><b>Invalidate anything.</b> Switching is a navigation action a person may click
     *       twice. It writes one column of one session row and mints a fresh access token; the
     *       refresh token keeps working untouched, other sessions are not consulted, and doing it
     *       again is the same operation with the same result.</li>
     * </ul>
     */
    @SystemTenant("re-scopes a session from one organization to another, so it is in neither while it decides")
    public AuthResponse switchOrganization(UUID userId, SwitchOrganizationRequest request, String refreshToken) {
        UserSession session = requireOwnLiveSession(userId, refreshToken);

        Membership membership = membershipRepository
                .findByUserIdAndOrganizationId(userId, request.getOrganizationId())
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

    /**
     * The session the presented refresh token names, once it has been shown to be live and to
     * belong to this user.
     *
     * <p>Looked up by jti rather than by the {@code sid} claim: the jti is rotated on every
     * refresh, so a token carrying a correct {@code sid} but a superseded jti — the shape a
     * replayed token has — finds nothing here.
     */
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

        // Reject anything that isn't an actual refresh token: an access token (or any
        // legacy token, which has no "typ" claim at all) must not be exchangeable here.
        // A missing claim is treated as invalid rather than grandfathered.
        if (!JwtUtil.TOKEN_TYPE_REFRESH.equals(jwtUtil.getTokenType(refreshToken))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }

        String oldJti = jwtUtil.getJtiFromToken(refreshToken);
        UUID userId = jwtUtil.getUserIdFromToken(refreshToken);

        if (tokenBlacklistService.isBlacklisted(oldJti)) {
            // Reuse detection: this refresh token was already consumed (rotated away on a
            // prior refresh, or explicitly revoked via logout). A rotated-away token being
            // replayed is the signature of a stolen refresh token racing the legitimate
            // client, so treat it as a compromised token family and kill every token the
            // user currently holds, not just this one.
            tokenBlacklistService.revokeAllUserTokens(userId);
            log.warn("Rejected reuse of already-rotated/revoked refresh token for user {}; revoked all tokens", userId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token has been revoked");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is disabled");
        }

        UserSession session = userSessionService.findByRefreshJti(oldJti).orElse(null);

        if (session == null && jwtUtil.getSessionIdFromToken(refreshToken) != null) {
            // The token names a session, and this jti is not the one that session accepts. That
            // is either a token rotated away in a refresh whose blacklist entry has since expired
            // or been lost with Redis, or a session that has been signed out. Neither may be
            // exchanged for a new pair, and the durable row -- not Redis -- is what says so.
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
            // A refresh token minted before sessions existed. Rather than refuse it -- which
            // would sign out everybody who was logged in across the upgrade -- give it the
            // session it should have had, so it appears in the list from here on.
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

    /**
     * Which organization a refreshed token names.
     *
     * <p>The session remembers what the switcher chose, so a refresh does not undo it — which is
     * what made a second organization unreachable before: login picked the oldest membership and
     * refresh picked it again fifteen minutes later.
     *
     * <p>A membership that has since been removed — or suspended — falls back to the oldest one
     * the user still holds, rather than failing: losing the organization you happened to be
     * looking at should not lock you out of the others. The session is moved with it, so the next
     * refresh does not have to work it out again.
     */
    private Membership membershipForRefresh(User user, UserSession session) {
        if (session != null) {
            Optional<Membership> remembered = membershipRepository
                    .findByUserIdAndOrganizationId(user.getId(), session.getOrganizationId())
                    // A suspension has to reach the token the session is already holding.
                    // Without this, suspending a member left them refreshing their way to a
                    // fresh fifteen minutes indefinitely, because the session still named an
                    // organization they were still technically a member of.
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

    /**
     * The membership a freshly minted token will name, and the one place a suspension is
     * refused.
     *
     * <p>Ordered, because findFirst() over an unordered query made this a coin toss: a user in
     * two organizations got whichever the database felt like returning, and with it a different
     * tenant scope on each login. Oldest membership is where a session starts; the organization
     * switcher moves it from there, and the session remembers.
     *
     * <p>A suspended membership is skipped rather than fatal, because a suspension belongs to one
     * organization: somebody suspended by one customer is still the other customer's member, and
     * refusing the login outright would lock them out of an organization that never asked for it.
     * Only when every membership is suspended is there nothing to issue a token for — and that is
     * a 403, not the 404 of a user who belongs to no organization at all.
     *
     * <p>INVITED is deliberately not skipped: that is the membership an invitee signs in with in
     * order to accept the invite.
     */
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
            // The row too, or the session a user just signed out of would still be sitting in
            // their session list looking live.
            userSessionService.findByRefreshJti(jwtUtil.getJtiFromToken(refreshToken))
                    .ifPresent(session -> userSessionService.revokeSession(session.getUserId(), session.getId()));
        }
    }

    /** Every live session for the caller, with the one making the request flagged. */
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
        log.info("Email verified for user {}", user.getEmail());
    }

    @SystemTenant("acts on a User by email address, with no authenticated caller")
    @Transactional
    public void resendVerification(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is already verified");
        }

        String newToken = generateVerificationToken();
        user.setVerificationToken(CryptoUtils.hashApiKey(newToken));
        user.setVerificationTokenExpiresAt(Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS));
        userRepository.save(user);

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

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        if (currentPassword.equals(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different from current password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Someone who supplied their current password is present, whatever a failure counter
        // says about them.
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockoutExpiresAt(null);
        userRepository.save(user);
        // Access tokens are self-contained and nothing re-checks the database per request, so
        // without this every session opened with the old password stays valid until its TTL
        // runs out. Changing a password has to mean the old one no longer gets you anywhere.
        userSessionService.revokeAllSessions(userId);
        log.info("Password changed for user {}, all sessions revoked", userId);
    }

    @SystemTenant("acts on a User by email address, with no authenticated caller")
    @Auditable(action = AuditAction.PASSWORD_RESET_REQUESTED, resourceType = "Auth")
    @Transactional
    public void forgotPassword(String email) {
        User user = userRepository.findByEmail(email).orElse(null);

        // Always return success to prevent email enumeration
        if (user == null) {
            log.info("Password reset requested for non-existent email: {}", email);
            return;
        }

        // Only the hash is persisted; the plaintext token is emailed and
        // never stored, matching the invite-token pattern in MembershipService.
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
        // This is the unlock path, and it is the reason account lockout is not a denial of
        // service against a known email address: somebody a stranger locked out reaches their
        // account through their own mailbox, without waiting for a window to lapse or for an
        // administrator to be awake. See AccountLockoutService.
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockoutExpiresAt(null);
        userRepository.save(user);
        // The reset path is the one that matters most: it is how somebody recovers an account
        // that has been taken over. Leaving the attacker's already-issued access token valid
        // for the rest of its TTL hands them the account back for another quarter of an hour,
        // while the owner believes they have just locked them out.
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
                .build();
    }
}
