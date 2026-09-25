package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.EmailChangeRequestRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.VerificationEmailSendRepository;
import com.webhook.platform.api.domain.repository.UserIdentityRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// The user row is anonymised, not deleted: shared_debug_links references it without cascade,
// and the audit log is kept under GDPR Article 17(3)(b).
@Service
@Slf4j
public class AccountErasureService {

    // Reserved by RFC 2606 and resolves nowhere, so nothing can be delivered to it.
    private static final String ERASED_EMAIL_DOMAIN = "@erased.invalid";

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final OrganizationService organizationService;
    private final UserSessionService userSessionService;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserIdentityRepository userIdentityRepository;
    private final EmailChangeRequestRepository emailChangeRequestRepository;
    private final VerificationEmailSendRepository verificationEmailSendRepository;
    private final TunnelService tunnelService;

    public AccountErasureService(UserRepository userRepository,
                                 MembershipRepository membershipRepository,
                                 OrganizationService organizationService,
                                 UserSessionService userSessionService,
                                 TokenBlacklistService tokenBlacklistService,
                                 UserIdentityRepository userIdentityRepository,
                                 EmailChangeRequestRepository emailChangeRequestRepository,
                                 VerificationEmailSendRepository verificationEmailSendRepository,
                                 TunnelService tunnelService) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.organizationService = organizationService;
        this.userSessionService = userSessionService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userIdentityRepository = userIdentityRepository;
        this.emailChangeRequestRepository = emailChangeRequestRepository;
        this.verificationEmailSendRepository = verificationEmailSendRepository;
        this.tunnelService = tunnelService;
    }

    // Refusals happen before anything is written, so a refused caller has lost nothing.
    @SystemTenant("erasing a person spans every organization they belong to, not the request's own")
    @Auditable(action = AuditAction.USER_ERASED, resourceType = "User")
    @Transactional
    public void eraseAccount(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        List<Membership> memberships = membershipRepository.findByUserId(userId);
        List<UUID> organizationsToDelete = decideOrganizations(memberships);

        log.warn("GDPR ERASE: erasing user {} — {} membership(s), {} organization(s) to delete",
                userId, memberships.size(), organizationsToDelete.size());

        if (!memberships.isEmpty()) {
            membershipRepository.deleteAll(memberships);
        }
        for (UUID organizationId : organizationsToDelete) {
            organizationService.deleteOrganizationById(organizationId);
        }

        anonymise(user);
        userRepository.save(user);
        // A Google link is keyed on Google's id, not the address, and would sign them straight back in.
        userIdentityRepository.deleteByUserId(userId);
        // These rows hold every address the account ever used.
        emailChangeRequestRepository.deleteByUserId(userId);
        verificationEmailSendRepository.deleteByUserId(userId);

        // Issued access tokens are stateless and would otherwise work until they expire.
        userSessionService.revokeAllSessions(userId);
        tokenBlacklistService.revokeAllUserTokens(userId);
        // A tunnel CLI never presents an access token, so the revocations above do not reach it.
        tunnelService.closeAllSessionsOfUserEverywhere(userId);

        log.info("GDPR ERASE: user {} erased", userId);
    }

    private List<UUID> decideOrganizations(List<Membership> memberships) {
        List<UUID> soleMemberOrganizations = new ArrayList<>();
        for (Membership membership : memberships) {
            UUID organizationId = membership.getOrganizationId();
            long members = membershipRepository.countByOrganizationId(organizationId);

            if (members <= 1) {
                soleMemberOrganizations.add(organizationId);
                continue;
            }
            if (membership.getRole() == MembershipRole.OWNER && lastOwner(organizationId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "You are the last owner of an organization that still has other members. "
                                + "Make someone else an owner, or delete the organization, and then "
                                + "erase your account.");
            }
        }
        return soleMemberOrganizations;
    }

    private boolean lastOwner(UUID organizationId) {
        return membershipRepository.countByOrganizationIdAndRoleAndStatusNot(
                organizationId, MembershipRole.OWNER, MembershipStatus.DISABLED) <= 1;
    }

    // The password hash is randomised, not nulled: a null hash means "no password set" to login.
    private void anonymise(User user) {
        user.setEmail(user.getId() + ERASED_EMAIL_DOMAIN);
        user.setFullName(null);
        user.setPasswordHash(CryptoUtils.generateSecureToken(32));
        user.setStatus(UserStatus.DISABLED);
        user.setEmailVerified(false);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiresAt(null);
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiresAt(null);
    }
}
