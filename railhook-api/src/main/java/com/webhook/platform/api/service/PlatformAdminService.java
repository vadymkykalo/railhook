package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AuditLog;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.AuditLogRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AdminAuditEntryResponse;
import com.webhook.platform.api.dto.AdminMemberResponse;
import com.webhook.platform.api.dto.AdminOrganizationResponse;
import com.webhook.platform.api.dto.AdminProjectResponse;
import com.webhook.platform.api.dto.UsageResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.service.billing.BillingOverviewService;
import com.webhook.platform.api.service.billing.BillingPeriod;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What an operator can see and do about a tenant, without a database client.
 *
 * <p>Everything under {@code /api/v1/admin/**} used to be one endpoint that rotates encryption
 * keys. An operator handling an abuse report, a support request, or a customer asking why their
 * deliveries stopped had psql and the logs — which is not a tool anyone should have to reach for
 * while a tenant is waiting, and is a poor place to make a decision that affects a paying
 * customer.
 *
 * <p>Every method here runs across organizations rather than inside one, which is the definition
 * of the platform admin: it belongs to whoever runs the deployment, and to no tenant. That is also
 * why none of it can be reached by a tenant role however privileged — SecurityConfig requires
 * {@code PLATFORM_ADMIN}, which only the operator token and a listed, verified, recent sign-in
 * carry.
 *
 * <p>The detail methods that read inside one organization — members, projects, audit log, usage —
 * enter that organization's scope with {@link TenantContext#callAs} and let {@code @TenantId}
 * confine the query, rather than filtering by the id by hand. They are not {@code @Transactional}
 * for the reason {@link #getUsage} gives.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformAdminService {

    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final MembershipRepository membershipRepository;
    private final EventRepository eventRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final PlatformAdminAccountFacts accountFacts;
    private final PlatformAdminAccessService platformAdminAccessService;
    private final SuspensionLookup suspensionLookup;
    private final BillingOverviewService billingOverviewService;
    private final Clock clock;

    @SystemTenant("the operator listing every tenant belongs to none of them")
    @Transactional(readOnly = true)
    public Page<AdminOrganizationResponse> listOrganizations(String search, boolean suspendedOnly,
            Pageable pageable) {
        String normalized = (search == null || search.isBlank()) ? null : search.trim();
        Page<Organization> page = organizationRepository.searchForOperator(normalized, suspendedOnly, pageable);
        List<UUID> ids = page.getContent().stream().map(Organization::getId).toList();
        Map<UUID, String> owners = ownerEmails(ids);
        Map<UUID, Long> events = eventsThisMonth(ids);
        return page.map(organization -> toResponse(organization, owners, events));
    }

    @SystemTenant("the operator inspecting one tenant is not a member of it")
    @Transactional(readOnly = true)
    public AdminOrganizationResponse getOrganization(UUID organizationId) {
        return toResponse(requireOrganization(organizationId));
    }

    /**
     * Stops an organization changing anything, until an operator says otherwise.
     *
     * <p>Idempotent on purpose: suspending an already-suspended organization refreshes the
     * reason rather than failing, because the operator doing it is usually reacting to a second
     * report and the useful outcome is the newer reason, not an error.
     *
     * <p>Deliberately does not touch {@code billingStatus}. That column belongs to the payment
     * state machine, and a suspension recorded there would be lifted by the next successful
     * charge — which is precisely the wrong behaviour for an abuse control.
     */
    @SystemTenant("suspension is an operator action on a tenant, taken from outside it")
    @Transactional
    public AdminOrganizationResponse suspend(UUID organizationId, String reason, String suspendedBy) {
        Organization organization = requireOrganization(organizationId);

        Instant now = Instant.now(clock);
        boolean wasAlreadySuspended = organization.isSuspended();
        organization.setSuspendedAt(wasAlreadySuspended ? organization.getSuspendedAt() : now);
        organization.setSuspensionReason(reason);
        organization.setSuspendedBy(suspendedBy);
        organizationRepository.save(organization);

        // Before returning, so the operator's next request sees the state they just set rather
        // than the TTL's idea of it.
        suspensionLookup.evict(organizationId);

        log.warn("Organization {} suspended by operator ({}): {}",
                organizationId,
                LogSanitizer.forLog(suspendedBy),
                LogSanitizer.forLog(reason));
        return toResponse(organization);
    }

    @SystemTenant("lifting a suspension is an operator action on a tenant, taken from outside it")
    @Transactional
    public AdminOrganizationResponse reinstate(UUID organizationId) {
        Organization organization = requireOrganization(organizationId);

        organization.setSuspendedAt(null);
        organization.setSuspensionReason(null);
        organization.setSuspendedBy(null);
        organizationRepository.save(organization);
        suspensionLookup.evict(organizationId);

        log.warn("Organization {} reinstated by operator", organizationId);
        return toResponse(organization);
    }

    /**
     * What one tenant has used against the plan they are on.
     *
     * <p>"Are they near their limit" is the question behind most support tickets that reach an
     * operator, and the back-office could not answer it: the list carried a plan name and two
     * row counts, and everything else meant a psql session against the customer's tables.
     *
     * <p>Answered by entering the subject's tenant scope and asking the same service the tenant's
     * own billing page asks, rather than by a second set of queries taking an organization id.
     * A parallel implementation is how the operator's numbers and the customer's numbers come to
     * disagree, which is the one thing a support conversation cannot survive.
     *
     * <p>Not {@code @Transactional}: the scope is entered around the call, so it must be entered
     * before any transaction opens rather than switched underneath one that is already running.
     */
    @SystemTenant("the operator asking about a tenant's usage is not a member of it")
    public UsageResponse getUsage(UUID organizationId) {
        requireExists(organizationId);
        return TenantContext.callAs(organizationId, billingOverviewService::usage);
    }

    /** Who is in the organization, in what role, and how each of them signs in. */
    @SystemTenant("the operator reading a tenant's members is not one of them")
    public Page<AdminMemberResponse> listMembers(UUID organizationId, Pageable pageable) {
        requireExists(organizationId);
        Page<Membership> page = TenantContext.callAs(organizationId,
                () -> membershipRepository.findAllWithUser(pageable));

        List<User> users = page.getContent().stream().map(Membership::getUser).filter(Objects::nonNull).toList();
        Map<UUID, List<String>> methods = accountFacts.signInMethods(users);
        Map<UUID, Instant> lastSeen = accountFacts.lastSeen(users.stream().map(User::getId).toList());

        return page.map(membership -> {
            User user = membership.getUser();
            return AdminMemberResponse.builder()
                    .userId(membership.getUserId())
                    .email(user == null ? null : user.getEmail())
                    .fullName(user == null ? null : user.getFullName())
                    .role(membership.getRole())
                    .membershipStatus(membership.getStatus())
                    .emailVerified(user != null && Boolean.TRUE.equals(user.getEmailVerified()))
                    .userStatus(user == null ? null : user.getStatus())
                    .signInMethods(methods.getOrDefault(membership.getUserId(), List.of()))
                    .joinedAt(membership.getCreatedAt())
                    .lastSeenAt(lastSeen.get(membership.getUserId()))
                    .platformAdmin(platformAdminAccessService.isPlatformAdmin(user))
                    .build();
        });
    }

    /** The organization's live projects — names only, nothing they contain. */
    @SystemTenant("the operator listing a tenant's projects is not a member of it")
    public Page<AdminProjectResponse> listProjects(UUID organizationId, Pageable pageable) {
        requireExists(organizationId);
        return TenantContext.callAs(organizationId, () -> projectRepository.findLive(pageable))
                .map(project -> AdminProjectResponse.builder()
                        .id(project.getId())
                        .name(project.getName())
                        .createdAt(project.getCreatedAt())
                        .build());
    }

    /**
     * The organization's own audit log, without the request bodies — see
     * {@link AdminAuditEntryResponse} for why those stay out.
     */
    @SystemTenant("the operator reading a tenant's audit log is not a member of it")
    public Page<AdminAuditEntryResponse> listAuditLog(UUID organizationId, Pageable pageable) {
        requireExists(organizationId);
        Page<AuditLog> page = TenantContext.callAs(organizationId, () -> auditLogRepository.findAll(pageable));

        List<UUID> actorIds = page.getContent().stream().map(AuditLog::getUserId).filter(Objects::nonNull)
                .distinct().toList();
        Map<UUID, String> emails = actorIds.isEmpty() ? Map.of() : userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));

        return page.map(entry -> AdminAuditEntryResponse.builder()
                .id(entry.getId())
                .action(entry.getAction())
                .resourceType(entry.getResourceType())
                .resourceId(entry.getResourceId())
                .actorEmail(entry.getUserId() == null ? null : emails.get(entry.getUserId()))
                .status(entry.getStatus())
                .clientIp(entry.getClientIp())
                .createdAt(entry.getCreatedAt())
                .build());
    }

    private Organization requireOrganization(UUID organizationId) {
        return organizationRepository.findByIdWithPlan(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found: " + organizationId));
    }

    private void requireExists(UUID organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new NotFoundException("Organization not found: " + organizationId);
        }
    }

    /** The earliest active OWNER of each organization, by address. */
    private Map<UUID, String> ownerEmails(Collection<UUID> organizationIds) {
        Map<UUID, String> owners = new HashMap<>();
        if (organizationIds.isEmpty()) {
            return owners;
        }
        for (Object[] row : membershipRepository.findEmailsByRole(organizationIds, MembershipRole.OWNER,
                MembershipStatus.ACTIVE)) {
            owners.putIfAbsent((UUID) row[0], (String) row[1]);
        }
        return owners;
    }

    /** Events in the current billing period — the figure the tenant's own usage page shows. */
    private Map<UUID, Long> eventsThisMonth(Collection<UUID> organizationIds) {
        if (organizationIds.isEmpty()) {
            return Map.of();
        }
        BillingPeriod period = BillingPeriod.current(clock);
        return eventRepository.countForOrganizationsBetween(organizationIds, period.start(), period.end())
                .stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).longValue()));
    }

    private AdminOrganizationResponse toResponse(Organization organization) {
        List<UUID> one = List.of(organization.getId());
        return toResponse(organization, ownerEmails(one), eventsThisMonth(one));
    }

    private AdminOrganizationResponse toResponse(Organization organization, Map<UUID, String> owners,
            Map<UUID, Long> events) {
        return AdminOrganizationResponse.builder()
                .id(organization.getId())
                .name(organization.getName())
                .planName(organization.getPlan() == null ? null : organization.getPlan().getName())
                .billingStatus(organization.getBillingStatus())
                .createdAt(organization.getCreatedAt())
                .ownerEmail(owners.get(organization.getId()))
                .projectCount(projectRepository.countByOrganizationIdAndDeletedAtIsNull(organization.getId()))
                .memberCount(membershipRepository.countByOrganizationId(organization.getId()))
                .eventsThisMonth(events.getOrDefault(organization.getId(), 0L))
                .eventsLimit(organization.getPlan() == null ? 0 : organization.getPlan().getMaxEventsPerMonth())
                .suspendedAt(organization.getSuspendedAt())
                .suspensionReason(organization.getSuspensionReason())
                .suspendedBy(organization.getSuspendedBy())
                .build();
    }
}
