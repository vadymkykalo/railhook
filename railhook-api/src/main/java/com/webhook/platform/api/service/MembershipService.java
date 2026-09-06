package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AddMemberRequest;
import com.webhook.platform.api.dto.MemberResponse;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;

import com.webhook.platform.common.util.CryptoUtils;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MembershipService {

    private static final int INVITE_EXPIRATION_HOURS = 48;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final EmailService emailService;
    private final TokenBlacklistService tokenBlacklistService;
    private final BCryptPasswordEncoder passwordEncoder;

    public MembershipService(
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            EmailService emailService,
            TokenBlacklistService tokenBlacklistService,
            BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.emailService = emailService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.passwordEncoder = passwordEncoder;
    }

    public List<MemberResponse> getOrganizationMembers() {
        UUID organizationId = TenantContext.require();
        List<Object[]> rows = membershipRepository.findMembersWithUsers(organizationId);

        return rows.stream()
                .map(row -> {
                    Membership membership = (Membership) row[0];
                    User user = (User) row[1];
                    return MemberResponse.builder()
                            .userId(user.getId())
                            .email(user.getEmail())
                            .role(membership.getRole())
                            .status(membership.getStatus())
                            .createdAt(membership.getCreatedAt())
                            // The expiry, so a pending invite can be seen running out and
                            // re-issued. Never the link: only the token's hash is stored,
                            // and a listing is readable by every member of the org.
                            .inviteExpiresAt(membership.getInviteExpiresAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Auditable(action = AuditAction.MEMBER_INVITED, resourceType = "Member")
    @Transactional
    public MemberResponse addMember(AddMemberRequest request, MembershipRole requestingRole) {
        UUID organizationId = TenantContext.require();
        if (requestingRole != MembershipRole.OWNER) {
            throw new ForbiddenException("Only owners can add members");
        }

        boolean isNewUser = !userRepository.existsByEmail(request.getEmail());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseGet(() -> {
                    String tempPass = generateTemporaryPassword();
                    User newUser = User.builder()
                            .email(request.getEmail())
                            .passwordHash(passwordEncoder.encode(tempPass))
                            .status(UserStatus.ACTIVE)
                            .build();
                    User saved = userRepository.save(newUser);
                    // The temp password is emailed directly to the invitee and is
                    // never logged, at any level — only non-secret metadata reaches the log.
                    emailService.sendTemporaryPasswordEmail(request.getEmail(), tempPass);
                    log.info("Created new user for invite: userId={}, email={}", saved.getId(), request.getEmail());
                    return saved;
                });

        if (membershipRepository.existsByUserIdAndOrganizationId(user.getId(), organizationId)) {
            throw new IllegalArgumentException("User is already a member");
        }

        String inviteToken = generateInviteToken();
        String inviteTokenHash = CryptoUtils.hashApiKey(inviteToken);
        Instant expiresAt = Instant.now().plus(INVITE_EXPIRATION_HOURS, ChronoUnit.HOURS);

        Membership membership = Membership.builder()
                .userId(user.getId())
                .organizationId(organizationId)
                .role(request.getRole())
                .status(isNewUser ? MembershipStatus.INVITED : MembershipStatus.ACTIVE)
                .inviteTokenHash(isNewUser ? inviteTokenHash : null)
                .inviteExpiresAt(isNewUser ? expiresAt : null)
                .build();
        membershipRepository.save(membership);

        log.info("Member added: userId={}, orgId={}, role={}, status={}",
                user.getId(), organizationId, request.getRole(), membership.getStatus());

        if (isNewUser) {
            emailService.sendInviteEmail(request.getEmail(), organizationId.toString(), inviteToken);
        }

        return MemberResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .role(membership.getRole())
                .status(membership.getStatus())
                .createdAt(membership.getCreatedAt())
                .inviteExpiresAt(membership.getInviteExpiresAt())
                // Handed back to the owner who issued it. With email delivery off — the
                // shipped default — sendInviteEmail only printed this to the container
                // log while the browser was told the invitation had been sent, so an
                // invite in a default install could not be delivered at all. The
                // temporary password stays where it is: see EmailService's javadoc.
                .inviteUrl(isNewUser ? emailService.inviteUrl(organizationId.toString(), inviteToken) : null)
                .build();
    }

    /**
     * Mints a fresh invite token for a membership still sitting at INVITED, replacing
     * whatever was issued before and starting the 48 hours again.
     *
     * <p>The previous token stops working the moment this returns — the row holds one
     * hash — which is what makes this the revoke-and-replace an expired invite needs.
     * No new temporary password is generated: the invitee's account already exists, and
     * a second non-expiring credential would be one more than anyone can deliver.
     */
    @Auditable(action = AuditAction.MEMBER_INVITED, resourceType = "Member")
    @Transactional
    public MemberResponse reissueInvite(UUID userId, MembershipRole requestingRole) {
        UUID organizationId = TenantContext.require();
        if (requestingRole != MembershipRole.OWNER) {
            throw new ForbiddenException("Only owners can re-issue invites");
        }

        Membership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new NotFoundException("Membership not found"));

        if (membership.getStatus() != MembershipStatus.INVITED) {
            throw new IllegalStateException("Membership has no pending invite to re-issue");
        }

        String inviteToken = generateInviteToken();
        membership.setInviteTokenHash(CryptoUtils.hashApiKey(inviteToken));
        membership.setInviteExpiresAt(Instant.now().plus(INVITE_EXPIRATION_HOURS, ChronoUnit.HOURS));
        membershipRepository.save(membership);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        log.info("Invite re-issued: userId={}, orgId={}, expiresAt={}",
                userId, organizationId, membership.getInviteExpiresAt());

        emailService.sendInviteEmail(user.getEmail(), organizationId.toString(), inviteToken);

        return MemberResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .role(membership.getRole())
                .status(membership.getStatus())
                .createdAt(membership.getCreatedAt())
                .inviteExpiresAt(membership.getInviteExpiresAt())
                .inviteUrl(emailService.inviteUrl(organizationId.toString(), inviteToken))
                .build();
    }

    /**
     * Accepting an invite crosses organizations by construction, which is why this one keeps an
     * explicit organization parameter and runs as the system tenant.
     *
     * <p>The invitee arrives holding a token for an organization they are <em>already</em> in —
     * their own — while the Membership row being accepted belongs to the inviting organization.
     * Reading the tenant from the ambient scope would look for the invite in the wrong place and
     * find nothing, and confining the lookup to that scope would make a valid invite a 404. So
     * {@code organizationId} here is the {@code {orgId}} path variable, and the checks below are
     * what enforce that the token, the organization and the caller all agree.
     */
    @SystemTenant("an invite is accepted by a user whose current tenant is a different organization")
    @Auditable(action = AuditAction.INVITE_ACCEPTED, resourceType = "Member")
    @Transactional
    public MemberResponse acceptInvite(UUID organizationId, String inviteToken, UUID authenticatedUserId) {
        String tokenHash = CryptoUtils.hashApiKey(inviteToken);
        Membership membership = membershipRepository.findByInviteTokenHash(tokenHash)
                .orElseThrow(() -> new NotFoundException("Invalid or expired invite token"));

        // Security: validate the invite belongs to the specified organization AND the authenticated user.
        // Use a single generic error message to prevent information leakage about which check failed.
        boolean orgMatch = membership.getOrganizationId().equals(organizationId);
        boolean userMatch = membership.getUserId().equals(authenticatedUserId);
        if (!orgMatch || !userMatch) {
            log.warn("Invite token validation failed: orgMatch={}, userMatch={}, " +
                            "tokenOrgId={}, requestOrgId={}, tokenUserId={}, authUserId={}",
                    orgMatch, userMatch,
                    membership.getOrganizationId(), organizationId,
                    membership.getUserId(), authenticatedUserId);
            throw new ForbiddenException("Invalid invite token");
        }

        if (membership.getStatus() != MembershipStatus.INVITED) {
            throw new IllegalStateException("Invite already accepted or membership is not in INVITED status");
        }

        if (membership.getInviteExpiresAt() != null && Instant.now().isAfter(membership.getInviteExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Invite token has expired");
        }

        membership.setStatus(MembershipStatus.ACTIVE);
        membership.setInviteTokenHash(null);
        membership.setInviteExpiresAt(null);
        membershipRepository.save(membership);

        User user = userRepository.findById(membership.getUserId())
                .orElseThrow(() -> new NotFoundException("User not found"));

        log.info("Invite accepted: userId={}, orgId={}", user.getId(), membership.getOrganizationId());

        return MemberResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .role(membership.getRole())
                .status(membership.getStatus())
                .createdAt(membership.getCreatedAt())
                .build();
    }

    private String generateTemporaryPassword() {
        return "Temp" + UUID.randomUUID().toString().substring(0, 8) + "!";
    }

    private String generateInviteToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Auditable(action = AuditAction.MEMBER_ROLE_CHANGED, resourceType = "Member")
    @Transactional
    public MemberResponse changeMemberRole(UUID userId, MembershipRole newRole,
            MembershipRole requestingRole) {
        UUID organizationId = TenantContext.require();
        if (requestingRole != MembershipRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owners can change member roles");
        }

        if (newRole == MembershipRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot assign OWNER role through this endpoint");
        }

        Membership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found"));

        if (membership.getRole() == MembershipRole.OWNER && ownersWhoCanStillSignIn(organizationId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot demote the last owner");
        }

        membership.setRole(newRole);
        membershipRepository.save(membership);
        // JwtAuthenticationFilter reads the role out of the access token and never re-checks
        // it against the database, so a demoted OWNER keeps OWNER authority until that token
        // expires. Revoking makes the demotion take effect on the next request instead.
        tokenBlacklistService.revokeAllUserTokens(userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        return MemberResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .role(membership.getRole())
                .status(membership.getStatus())
                .createdAt(membership.getCreatedAt())
                .build();
    }

    @Auditable(action = AuditAction.MEMBER_REMOVED, resourceType = "Member")
    @Transactional
    public void removeMember(UUID userId, MembershipRole requestingRole) {
        UUID organizationId = TenantContext.require();
        if (requestingRole != MembershipRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owners can remove members");
        }

        Membership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found"));

        if (membership.getRole() == MembershipRole.OWNER && ownersWhoCanStillSignIn(organizationId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove the last owner");
        }

        membershipRepository.delete(membership);
        // Same reasoning as the demotion above: organizationId comes from the token, so a
        // removed member goes on reaching this organization's data until it expires.
        // Refreshing is already blocked — that path 404s on the missing membership — which is
        // precisely why the live access token is the gap left to close.
        tokenBlacklistService.revokeAllUserTokens(userId);
    }

    /**
     * Takes a member's access away without taking their membership away.
     *
     * <p>The alternative was {@link #removeMember}, which deletes the row: a colleague on leave,
     * a stolen laptop or an offboarding still in progress all cost the record of who the person
     * was and what they held. A suspension keeps the row and the role, so reinstating is one
     * call and nothing has to be reconstructed from memory.</p>
     *
     * <p>Refusal happens where a membership becomes an authenticated context — {@code login} and
     * {@code refreshToken} in {@link AuthService} will not mint a token naming this organization
     * any more — plus the epoch revocation here, for the same reason a demotion and a removal do
     * it: the access token already in the member's hands is self-contained and re-checks nothing,
     * so without this the suspension would begin whenever that token happened to expire.</p>
     */
    @Auditable(action = AuditAction.MEMBER_SUSPENDED, resourceType = "Member")
    @Transactional
    public MemberResponse suspendMember(UUID userId, UUID requestingUserId, MembershipRole requestingRole) {
        UUID organizationId = TenantContext.require();
        if (requestingRole != MembershipRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owners can suspend members");
        }

        if (userId.equals(requestingUserId)) {
            // Nobody else can lift it for you unless another owner is left, and the check below
            // does not cover this case: an owner beside a second owner would pass it and lock
            // themselves out of their own organization.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot suspend yourself");
        }

        Membership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found"));

        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            // An INVITED membership is not access that can be withdrawn — it is an offer that can
            // be left to expire or removed. Suspending it would also have no way back: reinstating
            // sets ACTIVE, which would let the invitee in without ever accepting the invite.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an active member can be suspended");
        }

        if (membership.getRole() == MembershipRole.OWNER && ownersWhoCanStillSignIn(organizationId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot suspend the last owner");
        }

        membership.setStatus(MembershipStatus.DISABLED);
        membershipRepository.save(membership);
        tokenBlacklistService.revokeAllUserTokens(userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        log.info("Member suspended: userId={}, orgId={}, role={}", userId, organizationId, membership.getRole());

        return MemberResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .role(membership.getRole())
                .status(membership.getStatus())
                .createdAt(membership.getCreatedAt())
                .build();
    }

    /**
     * Gives a suspended member their access back, in the role the suspension preserved.
     *
     * <p>Nothing is revoked here: the epoch marker only invalidates tokens issued before it, so
     * a token minted after the reinstatement is valid without any further action.</p>
     */
    @Auditable(action = AuditAction.MEMBER_REINSTATED, resourceType = "Member")
    @Transactional
    public MemberResponse reinstateMember(UUID userId, MembershipRole requestingRole) {
        UUID organizationId = TenantContext.require();
        if (requestingRole != MembershipRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owners can reinstate members");
        }

        Membership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found"));

        if (membership.getStatus() != MembershipStatus.DISABLED) {
            // Said out loud rather than passed over: reinstating an INVITED membership would
            // turn an unaccepted invite into a full membership, and reinstating an ACTIVE one
            // means the caller is looking at a stale list.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Member is not suspended");
        }

        membership.setStatus(MembershipStatus.ACTIVE);
        membershipRepository.save(membership);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        log.info("Member reinstated: userId={}, orgId={}, role={}", userId, organizationId, membership.getRole());

        return MemberResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .role(membership.getRole())
                .status(membership.getStatus())
                .createdAt(membership.getCreatedAt())
                .build();
    }

    /**
     * How many owners could still sign in and administer this organization.
     *
     * <p>Deliberately not a count of owner rows. A suspended owner cannot sign in, so counting
     * one would let the last-owner guard be walked around in two steps: suspend one of two
     * owners, then remove or demote the other, and nobody is left who can administer the
     * organization — including nobody who can lift the suspension.</p>
     */
    private long ownersWhoCanStillSignIn(UUID organizationId) {
        return membershipRepository.countByOrganizationIdAndRoleAndStatusNot(
                organizationId, MembershipRole.OWNER, MembershipStatus.DISABLED);
    }
}
