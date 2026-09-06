import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { projectsApi } from './projects.api';
import { endpointsApi } from './endpoints.api';
import { deliveriesApi, type DeliveryFilters, type BulkReplayRequest } from './deliveries.api';
import { eventsApi } from './events.api';
import { subscriptionsApi, type SubscriptionRequest } from './subscriptions.api';
import { membersApi, type MembershipRole } from './members.api';
import { authApi } from './auth.api';
import { organizationsApi } from './organizations.api';
import { dashboardApi } from './dashboard.api';
import { dlqApi, type DlqFilters } from './dlq.api';
import { incomingDlqApi, type IncomingDlqFilters } from './incomingDlq.api';
import { auditLogApi, type AuditLogFilters } from './auditLog.api';
import { incomingSourcesApi } from './incomingSources.api';
import { incomingDestinationsApi } from './incomingDestinations.api';
import { incomingEventsApi, type IncomingEventFilters } from './incomingEvents.api';
import { schemasApi, type EventTypeCatalogRequest, type EventSchemaVersionRequest } from './schemas.api';
import { alertsApi, type AlertRuleRequest } from './alerts.api';
import { usageApi } from './usage.api';
import { incidentsApi, type IncidentRequest, type TimelineEntryRequest } from './incidents.api';
import { transformApi, type TransformPreviewRequest, type DeliveryDryRunRequest } from './transform.api';
import { transformationsApi } from './transformations.api';
import { rulesApi, type RuleRequest } from './rules.api';
import type { EndpointRequest, IncomingSourceRequest, IncomingDestinationRequest, IncomingBulkReplayRequest, TransformationRequest } from '../types/api.types';

// ─── Query Keys ────────────────────────────────────────────────────

export const queryKeys = {
    projects: {
        all: ['projects'] as const,
        detail: (id: string) => ['projects', id] as const,
    },
    dashboard: {
        stats: (projectId: string) => ['dashboard', 'stats', projectId] as const,
        analytics: (projectId: string, period: string) => ['dashboard', 'analytics', projectId, period] as const,
        onboarding: (projectId: string) => ['dashboard', 'onboarding', projectId] as const,
    },
    endpoints: {
        list: (projectId: string) => ['endpoints', projectId] as const,
        paged: (projectId: string, page: number, size: number) => ['endpoints', projectId, 'paged', page, size] as const,
    },
    deliveries: {
        list: (projectId: string, filters: DeliveryFilters) => ['deliveries', projectId, filters] as const,
    },
    events: {
        list: (projectId: string, page: number, size: number, sort?: string) => ['events', projectId, page, size, sort] as const,
        detail: (projectId: string, id: string) => ['events', projectId, id] as const,
    },
    subscriptions: {
        list: (projectId: string) => ['subscriptions', projectId] as const,
    },
    members: {
        list: (orgId: string) => ['members', orgId] as const,
    },
    apiKeys: {
        paged: (projectId: string, page: number, size: number) => ['api-keys', projectId, page, size] as const,
    },
    // Account-level rather than organization-level: a user's sessions and their organizations
    // are both things they hold across tenants, so neither key carries an organization.
    sessions: {
        all: ['auth', 'sessions'] as const,
    },
    organizations: {
        mine: ['organizations', 'mine'] as const,
    },
    dlq: {
        list: (projectId: string, page: number, size: number, filters?: DlqFilters) => ['dlq', projectId, page, size, filters ?? {}] as const,
    },
    incomingDlq: {
        list: (projectId: string, page: number, size: number, filters?: IncomingDlqFilters) => ['incoming-dlq', projectId, page, size, filters ?? {}] as const,
        stats: (projectId: string) => ['incoming-dlq', projectId, 'stats'] as const,
    },
    testEndpoints: {
        list: (projectId: string) => ['test-endpoints', projectId] as const,
        requests: (projectId: string, endpointId: string) => ['test-endpoints', projectId, 'requests', endpointId] as const,
    },
    auditLog: {
        list: (page: number, size: number, filters?: AuditLogFilters) => ['audit-log', page, size, filters] as const,
    },
    incomingSources: {
        list: (projectId: string, page: number, size: number) => ['incoming-sources', projectId, page, size] as const,
        all: (projectId: string) => ['incoming-sources', projectId] as const,
        detail: (projectId: string, id: string) => ['incoming-sources', projectId, id] as const,
    },
    incomingDestinations: {
        list: (projectId: string, sourceId: string, page: number, size: number) => ['incoming-destinations', projectId, sourceId, page, size] as const,
        all: (projectId: string, sourceId: string) => ['incoming-destinations', projectId, sourceId] as const,
    },
    incomingEvents: {
        list: (projectId: string, filters: IncomingEventFilters) => ['incoming-events', projectId, filters] as const,
        detail: (projectId: string, id: string) => ['incoming-events', projectId, id] as const,
        attempts: (projectId: string, eventId: string) => ['incoming-events', projectId, eventId, 'attempts'] as const,
    },
    schemas: {
        eventTypes: (projectId: string) => ['schemas', projectId] as const,
        versions: (projectId: string, eventTypeId: string) => ['schemas', projectId, eventTypeId, 'versions'] as const,
        changes: (projectId: string, eventTypeId: string) => ['schemas', projectId, eventTypeId, 'changes'] as const,
        projectChanges: (projectId: string) => ['schemas', projectId, 'all-changes'] as const,
    },
    alerts: {
        rules: (projectId: string) => ['alerts', 'rules', projectId] as const,
        events: (projectId: string, page: number, size: number) => ['alerts', 'events', projectId, page, size] as const,
        unresolvedCount: (projectId: string) => ['alerts', 'unresolved', projectId] as const,
    },
    usage: {
        stats: (projectId: string, days: number) => ['usage', projectId, days] as const,
    },
    incidents: {
        list: (projectId: string, openOnly: boolean, page: number) => ['incidents', projectId, openOnly, page] as const,
        detail: (projectId: string, incidentId: string) => ['incidents', projectId, incidentId] as const,
        openCount: (projectId: string) => ['incidents', 'open-count', projectId] as const,
    },
    transformations: {
        list: (projectId: string) => ['transformations', projectId] as const,
        detail: (projectId: string, id: string) => ['transformations', projectId, id] as const,
    },
    rules: {
        list: (projectId: string) => ['rules', projectId] as const,
        detail: (projectId: string, id: string) => ['rules', projectId, id] as const,
    },
} as const;

// ─── Projects ──────────────────────────────────────────────────────

export function useProjects() {
    return useQuery({
        queryKey: queryKeys.projects.all,
        queryFn: () => projectsApi.list(),
        staleTime: 0,
    });
}

export function useProject(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.projects.detail(projectId!),
        queryFn: () => projectsApi.get(projectId!),
        enabled: !!projectId,
    });
}

export function useCreateProject() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: { name: string; description?: string }) => projectsApi.create(data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.projects.all }); },
    });
}

export function useDeleteProject() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => projectsApi.delete(id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.projects.all }); },
    });
}

// ─── Dashboard ─────────────────────────────────────────────────────

export function useDashboardStats(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.dashboard.stats(projectId!),
        queryFn: () => dashboardApi.getProjectStats(projectId!),
        enabled: !!projectId,
        staleTime: 0,
    });
}

export function useAnalytics(projectId: string | undefined, period: string) {
    return useQuery({
        queryKey: queryKeys.dashboard.analytics(projectId!, period),
        queryFn: () => dashboardApi.getAnalytics(projectId!, period),
        enabled: !!projectId,
    });
}

export function useOnboardingStatus(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.dashboard.onboarding(projectId!),
        queryFn: () => dashboardApi.getOnboardingStatus(projectId!),
        enabled: !!projectId,
        staleTime: 30_000,
    });
}

// ─── Endpoints ─────────────────────────────────────────────────────

export function useEndpoints(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.endpoints.list(projectId!),
        queryFn: () => endpointsApi.list(projectId!),
        enabled: !!projectId,
        staleTime: 0,
    });
}

export function useEndpointsPaged(projectId: string | undefined, page: number, size = 20) {
    return useQuery({
        queryKey: queryKeys.endpoints.paged(projectId!, page, size),
        queryFn: () => endpointsApi.listPaged(projectId!, page, size),
        enabled: !!projectId,
    });
}

export function useCreateEndpoint(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: EndpointRequest) => endpointsApi.create(projectId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['endpoints', projectId] }); },
    });
}

export function useDeleteEndpoint(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => endpointsApi.delete(projectId, id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['endpoints', projectId] }); },
    });
}

export function useUpdateEndpoint(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, data }: { id: string; data: EndpointRequest }) => endpointsApi.update(projectId, id, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['endpoints', projectId] }); },
    });
}

export function useRotateSecret(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => endpointsApi.rotateSecret(projectId, id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['endpoints', projectId] }); },
    });
}

export function useVerifyEndpoint(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => endpointsApi.verify(projectId, id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['endpoints', projectId] }); },
    });
}

export function useSkipVerification(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, reason }: { id: string; reason?: string }) => endpointsApi.skipVerification(projectId, id, reason),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['endpoints', projectId] }); },
    });
}

// ─── Deliveries ────────────────────────────────────────────────────

export function useDeliveries(projectId: string | undefined, filters: DeliveryFilters) {
    return useQuery({
        queryKey: queryKeys.deliveries.list(projectId!, filters),
        queryFn: () => deliveriesApi.listByProject(projectId!, filters),
        enabled: !!projectId,
        // Poll only while there are deliveries still in flight — replaces the
        // page-level setInterval + eslint-disable react-hooks/exhaustive-deps.
        refetchInterval: (query) => {
            const hasActive = query.state.data?.content?.some(
                (d) => d.status === 'PENDING' || d.status === 'PROCESSING'
            );
            return hasActive ? 5000 : false;
        },
    });
}

export function useBulkReplayDeliveries() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (request: BulkReplayRequest) => deliveriesApi.bulkReplay(request),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['deliveries'] }); },
    });
}

// ─── Events ────────────────────────────────────────────────────────

export function useEvents(projectId: string | undefined, page: number, size = 20, sort = 'createdAt,desc', eventType?: string) {
    return useQuery({
        queryKey: [...queryKeys.events.list(projectId!, page, size, sort), eventType ?? ''],
        queryFn: () => eventsApi.listByProject(projectId!, { page, size, sort, eventType }),
        enabled: !!projectId,
    });
}

export function useEvent(projectId: string | undefined, eventId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.events.detail(projectId!, eventId!),
        queryFn: () => eventsApi.get(projectId!, eventId!),
        enabled: !!projectId && !!eventId,
    });
}

// ─── Subscriptions ─────────────────────────────────────────────────

export function useSubscriptions(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.subscriptions.list(projectId!),
        queryFn: () => subscriptionsApi.list(projectId!),
        enabled: !!projectId,
        staleTime: 0,
    });
}

export function usePatchSubscription(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, data }: { id: string; data: Partial<SubscriptionRequest> }) => subscriptionsApi.patch(projectId, id, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.subscriptions.list(projectId) }); },
    });
}

export function useDeleteSubscription(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => subscriptionsApi.delete(projectId, id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.subscriptions.list(projectId) }); },
    });
}

// ─── Members ───────────────────────────────────────────────────────

export function useMembers(orgId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.members.list(orgId!),
        queryFn: () => membersApi.list(orgId!),
        enabled: !!orgId,
    });
}

export function useChangeMemberRole(orgId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ userId, role }: { userId: string; role: MembershipRole }) =>
            membersApi.changeRole(orgId, userId, { role }),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.members.list(orgId) }); },
    });
}

export function useReissueInvite(orgId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (userId: string) => membersApi.reissueInvite(orgId, userId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.members.list(orgId) }); },
    });
}

export function useRemoveMember(orgId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (userId: string) => membersApi.remove(orgId, userId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.members.list(orgId) }); },
    });
}

export function useSuspendMember(orgId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (userId: string) => membersApi.suspend(orgId, userId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.members.list(orgId) }); },
    });
}

export function useReinstateMember(orgId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (userId: string) => membersApi.reinstate(orgId, userId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.members.list(orgId) }); },
    });
}

// ─── API Keys ──────────────────────────────────────────────────────

// ─── Sessions ──────────────────────────────────────────────────────

export function useSessions() {
    return useQuery({
        queryKey: queryKeys.sessions.all,
        queryFn: () => authApi.listSessions(),
        // A stale session list is worse than a slow one: it invites somebody to revoke a device
        // that is already gone, or to miss one that is not.
        staleTime: 0,
    });
}

export function useRevokeSession() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (sessionId: string) => authApi.revokeSession(sessionId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.sessions.all }); },
    });
}

export function useRevokeAllSessions() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: () => authApi.revokeAllSessions(),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.sessions.all }); },
    });
}

// ─── Organizations ─────────────────────────────────────────────────

export function useOrganizations() {
    return useQuery({
        queryKey: queryKeys.organizations.mine,
        queryFn: () => organizationsApi.list(),
    });
}

// ─── DLQ ───────────────────────────────────────────────────────────

export function useDlq(projectId: string | undefined, page: number, size = 20, filters?: DlqFilters) {
    return useQuery({
        queryKey: queryKeys.dlq.list(projectId!, page, size, filters),
        queryFn: () => dlqApi.list(projectId!, page, size, filters),
        enabled: !!projectId,
    });
}

export function useDlqStats(projectId: string | undefined) {
    return useQuery({
        queryKey: ['dlq', projectId, 'stats'] as const,
        queryFn: () => dlqApi.getStats(projectId!),
        enabled: !!projectId,
    });
}

export function useDlqRetry(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (deliveryId: string) => dlqApi.retrySingle(projectId, deliveryId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['dlq', projectId] }); },
    });
}

export function useDlqBulkRetry(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (ids: string[]) => dlqApi.retryBulk(projectId, ids),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['dlq', projectId] }); },
    });
}

export function useDlqPurge(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: () => dlqApi.purgeAll(projectId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['dlq', projectId] }); },
    });
}

// ─── Incoming DLQ ──────────────────────────────────────────────────

export function useIncomingDlq(projectId: string | undefined, page: number, size = 20, filters?: IncomingDlqFilters) {
    return useQuery({
        queryKey: queryKeys.incomingDlq.list(projectId!, page, size, filters),
        queryFn: () => incomingDlqApi.list(projectId!, page, size, filters),
        enabled: !!projectId,
    });
}

export function useIncomingDlqStats(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.incomingDlq.stats(projectId!),
        queryFn: () => incomingDlqApi.getStats(projectId!),
        enabled: !!projectId,
    });
}

export function useIncomingDlqRetry(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (forwardAttemptId: string) => incomingDlqApi.retrySingle(projectId, forwardAttemptId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-dlq', projectId] }); },
    });
}

export function useIncomingDlqBulkRetry(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (ids: string[]) => incomingDlqApi.retryBulk(projectId, ids),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-dlq', projectId] }); },
    });
}

export function useIncomingDlqPurge(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: () => incomingDlqApi.purgeAll(projectId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-dlq', projectId] }); },
    });
}

// ─── Test Endpoints ────────────────────────────────────────────────

// ─── Audit Log ─────────────────────────────────────────────────────

export function useAuditLog(page: number, size = 20, filters?: AuditLogFilters) {
    return useQuery({
        queryKey: queryKeys.auditLog.list(page, size, filters),
        queryFn: () => auditLogApi.list(page, size, filters),
    });
}

// ─── Incoming Sources ─────────────────────────────────────────────

export function useIncomingSources(projectId: string | undefined, page: number, size = 20) {
    return useQuery({
        queryKey: queryKeys.incomingSources.list(projectId!, page, size),
        queryFn: () => incomingSourcesApi.list(projectId!, page, size),
        enabled: !!projectId,
        staleTime: 0,
    });
}

export function useIncomingSource(projectId: string | undefined, id: string | undefined) {
    return useQuery({
        queryKey: queryKeys.incomingSources.detail(projectId!, id!),
        queryFn: () => incomingSourcesApi.get(projectId!, id!),
        enabled: !!projectId && !!id,
    });
}

export function useCreateIncomingSource(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: IncomingSourceRequest) => incomingSourcesApi.create(projectId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-sources', projectId] }); },
    });
}

export function useUpdateIncomingSource(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, data }: { id: string; data: IncomingSourceRequest }) => incomingSourcesApi.update(projectId, id, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-sources', projectId] }); },
    });
}

export function useDeleteIncomingSource(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => incomingSourcesApi.delete(projectId, id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-sources', projectId] }); },
    });
}

// ─── Incoming Destinations ────────────────────────────────────────

export function useIncomingDestinations(projectId: string | undefined, sourceId: string | undefined, page: number, size = 20) {
    return useQuery({
        queryKey: queryKeys.incomingDestinations.list(projectId!, sourceId!, page, size),
        queryFn: () => incomingDestinationsApi.list(projectId!, sourceId!, page, size),
        enabled: !!projectId && !!sourceId,
    });
}

export function useCreateIncomingDestination(projectId: string, sourceId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: IncomingDestinationRequest) => incomingDestinationsApi.create(projectId, sourceId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-destinations', projectId, sourceId] }); },
    });
}

export function useUpdateIncomingDestination(projectId: string, sourceId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, data }: { id: string; data: IncomingDestinationRequest }) => incomingDestinationsApi.update(projectId, sourceId, id, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-destinations', projectId, sourceId] }); },
    });
}

export function useDeleteIncomingDestination(projectId: string, sourceId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => incomingDestinationsApi.delete(projectId, sourceId, id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-destinations', projectId, sourceId] }); },
    });
}

// ─── Incoming Events ──────────────────────────────────────────────

export function useIncomingEvents(projectId: string | undefined, filters: IncomingEventFilters) {
    return useQuery({
        queryKey: queryKeys.incomingEvents.list(projectId!, filters),
        queryFn: () => incomingEventsApi.list(projectId!, filters),
        enabled: !!projectId,
    });
}

export function useIncomingEventAttempts(projectId: string | undefined, eventId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.incomingEvents.attempts(projectId!, eventId!),
        queryFn: () => incomingEventsApi.getAttempts(projectId!, eventId!),
        enabled: !!projectId && !!eventId,
    });
}

export function useReplayIncomingEvent(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (eventId: string) => incomingEventsApi.replay(projectId, eventId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-events', projectId] }); },
    });
}

export function useBulkReplayIncomingEvents(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (request: IncomingBulkReplayRequest) => incomingEventsApi.bulkReplay(projectId, request),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incoming-events', projectId] }); },
    });
}

// ─── Schema Registry ─────────────────────────────────────────────

export function useEventTypes(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.schemas.eventTypes(projectId!),
        queryFn: () => schemasApi.listEventTypes(projectId!),
        enabled: !!projectId,
    });
}

export function useCreateEventType(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: EventTypeCatalogRequest) => schemasApi.createEventType(projectId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.schemas.eventTypes(projectId) }); },
    });
}

/**
 * Rename an event type, or give it the description it never got.
 *
 * <p>`PUT /schemas/{eventTypeId}` shipped with the rest of the registry and was
 * never called, so a typo in an event type's name was permanent: the catalogue
 * could create and delete, and nothing in between.
 */
export function useUpdateEventType(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, data }: { id: string; data: EventTypeCatalogRequest }) =>
            schemasApi.updateEventType(projectId, id, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.schemas.eventTypes(projectId) }); },
    });
}

export function useDeleteEventType(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => schemasApi.deleteEventType(projectId, id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.schemas.eventTypes(projectId) }); },
    });
}

export function useSchemaVersions(projectId: string | undefined, eventTypeId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.schemas.versions(projectId!, eventTypeId!),
        queryFn: () => schemasApi.listVersions(projectId!, eventTypeId!),
        enabled: !!projectId && !!eventTypeId,
    });
}

export function useCreateSchemaVersion(projectId: string, eventTypeId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: EventSchemaVersionRequest) => schemasApi.createVersion(projectId, eventTypeId, data),
        onSuccess: () => {
            qc.invalidateQueries({ queryKey: queryKeys.schemas.versions(projectId, eventTypeId) });
            qc.invalidateQueries({ queryKey: queryKeys.schemas.eventTypes(projectId) });
            qc.invalidateQueries({ queryKey: queryKeys.schemas.changes(projectId, eventTypeId) });
        },
    });
}

export function usePromoteSchema(projectId: string, eventTypeId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (versionId: string) => schemasApi.promoteVersion(projectId, eventTypeId, versionId),
        onSuccess: () => {
            qc.invalidateQueries({ queryKey: queryKeys.schemas.versions(projectId, eventTypeId) });
            qc.invalidateQueries({ queryKey: queryKeys.schemas.eventTypes(projectId) });
        },
    });
}

export function useDeprecateSchema(projectId: string, eventTypeId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (versionId: string) => schemasApi.deprecateVersion(projectId, eventTypeId, versionId),
        onSuccess: () => {
            qc.invalidateQueries({ queryKey: queryKeys.schemas.versions(projectId, eventTypeId) });
            qc.invalidateQueries({ queryKey: queryKeys.schemas.eventTypes(projectId) });
        },
    });
}

export function useSchemaChanges(projectId: string | undefined, eventTypeId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.schemas.changes(projectId!, eventTypeId!),
        queryFn: () => schemasApi.listChanges(projectId!, eventTypeId!),
        enabled: !!projectId && !!eventTypeId,
    });
}

export function useProjectSchemaChanges(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.schemas.projectChanges(projectId!),
        queryFn: () => schemasApi.listProjectChanges(projectId!),
        enabled: !!projectId,
    });
}

// ─── Alerts ──────────────────────────────────────────────────────

export function useAlertRules(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.alerts.rules(projectId!),
        queryFn: () => alertsApi.listRules(projectId!),
        enabled: !!projectId,
    });
}

export function useCreateAlertRule(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: AlertRuleRequest) => alertsApi.createRule(projectId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.alerts.rules(projectId) }); },
    });
}

export function useUpdateAlertRule(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ ruleId, data }: { ruleId: string; data: Partial<AlertRuleRequest> }) =>
            alertsApi.updateRule(projectId, ruleId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.alerts.rules(projectId) }); },
    });
}

export function useDeleteAlertRule(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (ruleId: string) => alertsApi.deleteRule(projectId, ruleId),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.alerts.rules(projectId) }); },
    });
}

export function useAlertEvents(projectId: string | undefined, page: number, size = 20) {
    return useQuery({
        queryKey: queryKeys.alerts.events(projectId!, page, size),
        queryFn: () => alertsApi.listEvents(projectId!, page, size),
        enabled: !!projectId,
    });
}

export function useUnresolvedAlertCount(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.alerts.unresolvedCount(projectId!),
        queryFn: () => alertsApi.unresolvedCount(projectId!),
        enabled: !!projectId,
        refetchInterval: 30000,
    });
}

export function useResolveAlert(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (eventId: string) => alertsApi.resolveEvent(projectId, eventId),
        onSuccess: () => {
            qc.invalidateQueries({ queryKey: ['alerts', 'events', projectId] });
            qc.invalidateQueries({ queryKey: queryKeys.alerts.unresolvedCount(projectId) });
        },
    });
}

export function useResolveAllAlerts(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: () => alertsApi.resolveAll(projectId),
        onSuccess: () => {
            qc.invalidateQueries({ queryKey: ['alerts', 'events', projectId] });
            qc.invalidateQueries({ queryKey: queryKeys.alerts.unresolvedCount(projectId) });
        },
    });
}

// ─── Usage ──────────────────────────────────────────────────────

export function useUsageStats(projectId: string | undefined, days = 30) {
    return useQuery({
        queryKey: queryKeys.usage.stats(projectId!, days),
        queryFn: () => usageApi.getUsage(projectId!, days),
        enabled: !!projectId,
    });
}

// ─── Incidents ──────────────────────────────────────────────────

export function useIncidents(projectId: string | undefined, openOnly = false, page = 0, size = 20) {
    return useQuery({
        queryKey: queryKeys.incidents.list(projectId!, openOnly, page),
        queryFn: () => incidentsApi.list(projectId!, openOnly, page, size),
        enabled: !!projectId,
    });
}

export function useIncident(projectId: string | undefined, incidentId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.incidents.detail(projectId!, incidentId!),
        queryFn: () => incidentsApi.get(projectId!, incidentId!),
        enabled: !!projectId && !!incidentId,
    });
}

export function useOpenIncidentCount(projectId: string | undefined) {
    return useQuery({
        queryKey: queryKeys.incidents.openCount(projectId!),
        queryFn: () => incidentsApi.countOpen(projectId!),
        enabled: !!projectId,
    });
}

export function useCreateIncident(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: IncidentRequest) => incidentsApi.create(projectId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incidents', projectId] }); },
    });
}

export function useUpdateIncident(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ incidentId, data }: { incidentId: string; data: Partial<IncidentRequest> }) =>
            incidentsApi.update(projectId, incidentId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incidents', projectId] }); },
    });
}

export function useAddTimelineEntry(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ incidentId, data }: { incidentId: string; data: TimelineEntryRequest }) =>
            incidentsApi.addTimeline(projectId, incidentId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: ['incidents', projectId] }); },
    });
}

// ─── Transformations ────────────────────────────────────────────

export function useTransformations(projectId: string) {
    return useQuery({
        queryKey: queryKeys.transformations.list(projectId),
        queryFn: () => transformationsApi.list(projectId),
        enabled: !!projectId,
    });
}

export function useCreateTransformation(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: TransformationRequest) => transformationsApi.create(projectId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.transformations.list(projectId) }); },
    });
}

export function useUpdateTransformation(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, data }: { id: string; data: TransformationRequest }) =>
            transformationsApi.update(projectId, id, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.transformations.list(projectId) }); },
    });
}

export function useDeleteTransformation(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => transformationsApi.delete(projectId, id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.transformations.list(projectId) }); },
    });
}

// ─── Transform Preview ──────────────────────────────────────────

export function useTransformPreview(projectId: string) {
    return useMutation({
        mutationFn: (data: TransformPreviewRequest) => transformApi.preview(projectId, data),
    });
}

export function useDeliveryDryRun(projectId: string) {
    return useMutation({
        mutationFn: (data: DeliveryDryRunRequest) => transformApi.deliveryDryRun(projectId, data),
    });
}

// ─── Project Settings ───────────────────────────────────────────

export function useUpdateProject(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: { name: string; description?: string; schemaValidationEnabled?: boolean; schemaValidationPolicy?: string; idempotencyPolicy?: string }) =>
            projectsApi.update(projectId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.projects.detail(projectId) }); },
    });
}

// ─── Rules ──────────────────────────────────────────────────────

export function useRules(projectId: string) {
    return useQuery({
        queryKey: queryKeys.rules.list(projectId),
        queryFn: () => rulesApi.list(projectId),
        enabled: !!projectId,
    });
}

export function useCreateRule(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (data: RuleRequest) => rulesApi.create(projectId, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.rules.list(projectId) }); },
    });
}

export function useUpdateRule(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, data }: { id: string; data: RuleRequest }) =>
            rulesApi.update(projectId, id, data),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.rules.list(projectId) }); },
    });
}

export function useDeleteRule(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => rulesApi.delete(projectId, id),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.rules.list(projectId) }); },
    });
}

export function useToggleRule(projectId: string) {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
            rulesApi.toggle(projectId, id, enabled),
        onSuccess: () => { qc.invalidateQueries({ queryKey: queryKeys.rules.list(projectId) }); },
    });
}
