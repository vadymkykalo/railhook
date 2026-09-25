package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.domain.EmailAddresses;
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

import com.webhook.platform.api.exception.ConflictException;
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
    private final TunnelService tunnelService;

    public MembershipService(
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            EmailService emailService,
            TokenBlacklistService tokenBlacklistService,
            BCryptPasswordEncoder passwordEncoder,
            TunnelService tunnelService) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.emailService = emailService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.passwordEncoder = passwordEncoder;
        this.tunnelService = tunnelService;
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
                            // Never the invite link: only its hash is stored, and every member can list.
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
        requireGrantableRole(request.getRole());

        String email = EmailAddresses.normalize(request.getEmail());
        boolean isNewUser = !userRepository.existsByEmail(email);

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> {
                    String tempPass = generateTemporaryPassword();
                    User newUser = User.builder()
                            .email(email)
                            .passwordHash(passwordEncoder.encode(tempPass))
                            .status(UserStatus.ACTIVE)
                            .build();
                    User saved = userRepository.save(newUser);
                    // Never log the temp password.
                    emailService.sendTemporaryPasswordEmail(request.getEmail(), tempPass);
                    log.info("Created new user for invite: userId={}, email={}", saved.getId(), request.getEmail());
                    return saved;
                });

        if (membershipRepository.existsByUserIdAndOrganizationId(user.getId(), organizationId)) {
            throw new IllegalArgumentException("User is already a member");
        }

        // An unverified account may have been registered for someone else's address.
        if (!isNewUser && emailService.isEnabled() && !Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new ConflictException("An account with this address exists but has not verified it. "
                    + "Ask them to verify their email address, then invite them again.");
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
                // With email off (the default), this is the only way the invite reaches anyone.
                .inviteUrl(isNewUser ? emailService.inviteUrl(organizationId.toString(), inviteToken) : null)
                .build();
    }

    /** The row holds one hash, so the previous token stops working at once. */
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
            throw new ConflictException("Membership has no pending invite to re-issue");
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

    @SystemTenant("an invite is accepted by a user whose current tenant is a different organization")
    @Auditable(action = AuditAction.INVITE_ACCEPTED, resourceType = "Member")
    @Transactional
    public MemberResponse acceptInvite(UUID organizationId, String inviteToken, UUID authenticatedUserId) {
        String tokenHash = CryptoUtils.hashApiKey(inviteToken);
        Membership membership = membershipRepository.findByInviteTokenHash(tokenHash)
                .orElseThrow(() -> new NotFoundException("Invalid or expired invite token"));

        // One generic error so the caller cannot tell which check failed.
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
            throw new ConflictException("Invite already accepted or membership is not in INVITED status");
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

    /** OWNER is never granted here, and API_KEY is not a human role. */
    private static void requireGrantableRole(MembershipRole role) {
        if (role == MembershipRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot assign OWNER role through this endpoint");
        }
        if (role == MembershipRole.API_KEY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "API_KEY is not a role a member can hold");
        }
    }

    @Auditable(action = AuditAction.MEMBER_ROLE_CHANGED, resourceType = "Member")
    @Transactional
    public MemberResponse changeMemberRole(UUID userId, MembershipRole newRole,
            MembershipRole requestingRole) {
        UUID organizationId = TenantContext.require();
        if (requestingRole != MembershipRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owners can change member roles");
        }

        requireGrantableRole(newRole);

        Membership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found"));

        if (membership.getRole() == MembershipRole.OWNER && ownersWhoCanStillSignIn(organizationId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot demote the last owner");
        }

        membership.setRole(newRole);
        membershipRepository.save(membership);
        // The role in the access token is never re-checked, so a demoted OWNER would keep it.
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
        // The live access token still names this organization; refresh already fails.
        tokenBlacklistService.revokeAllUserTokens(userId);
        // The tunnel CLI never presents an access token, so its sessions outlive the revocation.
        tunnelService.closeSessionsOfUser(userId);
    }

    // Keeps the row and role so reinstating is one call; revocation covers issued access tokens.
    @Auditable(action = AuditAction.MEMBER_SUSPENDED, resourceType = "Member")
    @Transactional
    public MemberResponse suspendMember(UUID userId, UUID requestingUserId, MembershipRole requestingRole) {
        UUID organizationId = TenantContext.require();
        if (requestingRole != MembershipRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owners can suspend members");
        }

        if (userId.equals(requestingUserId)) {
            // Otherwise a second owner could become the only one able to lift it.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot suspend yourself");
        }

        Membership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found"));

        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            // Reinstating sets ACTIVE, which would let an invitee in without accepting.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an active member can be suspended");
        }

        if (membership.getRole() == MembershipRole.OWNER && ownersWhoCanStillSignIn(organizationId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot suspend the last owner");
        }

        membership.setStatus(MembershipStatus.DISABLED);
        membershipRepository.save(membership);
        tokenBlacklistService.revokeAllUserTokens(userId);
        tunnelService.closeSessionsOfUser(userId);

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

    /** Nothing to revoke: the epoch marker only invalidates tokens issued before it. */
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
            // Reinstating INVITED would skip accepting the invite; ACTIVE means a stale list.
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

    // A suspended owner cannot sign in, so counting one would let the last-owner guard be bypassed.
    private long ownersWhoCanStillSignIn(UUID organizationId) {
        return membershipRepository.countByOrganizationIdAndRoleAndStatusNot(
                organizationId, MembershipRole.OWNER, MembershipStatus.DISABLED);
    }
}
