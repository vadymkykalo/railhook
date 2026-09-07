package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
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

/**
 * GDPR Article 17 for a person, rather than for an organization.
 *
 * <p>{@code OrganizationService.deleteOrganization} has always covered a customer erasing
 * everything they hold. It could not cover a human being who is a member of somebody else's
 * organization and wants their own record gone — the half of the right that individuals
 * actually exercise, and the half this platform had no answer for.
 *
 * <h2>Why the row survives</h2>
 *
 * <p>Two things in the schema decide this, and neither is a preference:
 *
 * <ul>
 *   <li>{@code shared_debug_links.created_by} references {@code users(id)} with no cascade, so
 *       deleting the row outright fails for anyone who has ever shared a debug link — the
 *       erasure would work in testing and fail for exactly the people who used the product.</li>
 *   <li>{@code audit_log.user_id} has no foreign key at all, so what someone did outlives them.
 *       That is deliberate and is the reason an audit log is worth having; keeping it is a
 *       legitimate basis under Article 17(3)(b), and it holds no contact details of its own.</li>
 * </ul>
 *
 * <p>So the identifying data goes and the account is made permanently unusable, which is what
 * erasure means here. What remains is a row with an id and no person attached to it.
 *
 * <h2>Why an organization can block it</h2>
 *
 * <p>Leaving an organization ownerless would strand every other member: nobody could invite,
 * change a plan, or delete it afterwards. So the last owner of an organization other people
 * still belong to is refused and told to hand it over first. An organization the person is
 * alone in goes with them — otherwise erasure leaves every event, endpoint and delivery it
 * owned in the database with nobody able to reach them, which is the opposite of the request.
 */
@Service
@Slf4j
public class AccountErasureService {

    /**
     * The domain erased addresses are moved to. {@code .invalid} is reserved by RFC 2606 and
     * resolves nowhere, so nothing can ever be delivered to it by accident.
     */
    private static final String ERASED_EMAIL_DOMAIN = "@erased.invalid";

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final OrganizationService organizationService;
    private final UserSessionService userSessionService;
    private final TokenBlacklistService tokenBlacklistService;

    public AccountErasureService(UserRepository userRepository,
                                 MembershipRepository membershipRepository,
                                 OrganizationService organizationService,
                                 UserSessionService userSessionService,
                                 TokenBlacklistService tokenBlacklistService) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.organizationService = organizationService;
        this.userSessionService = userSessionService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * Erases one person. All of it or none of it: the refusals below happen before anything is
     * written, so a caller who is told to hand an organization over first has lost nothing.
     *
     * <p>{@code @SystemTenant} because this crosses organizations by construction — the
     * memberships being removed belong to every organization the person was in, not to the one
     * whose scope the request happens to carry.
     */
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

        // The session rows go, and the access tokens already issued are blacklisted: those are
        // stateless and would otherwise keep working until they expired on their own.
        userSessionService.revokeAllSessions(userId);
        tokenBlacklistService.revokeAllUserTokens(userId);

        log.info("GDPR ERASE: user {} erased", userId);
    }

    /**
     * Which of the person's organizations die with them — and whether any of them refuses to
     * let go. Runs to completion before anything is written so the refusal cannot land halfway.
     */
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

    /**
     * Replaces everything that identifies the person, and everything that could let the account
     * be used or recovered. The password hash is replaced rather than blanked because the column
     * is {@code NOT NULL} and because a blank one is a shape somebody's login path might one day
     * treat as "no password set".
     */
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
