import { useAuth } from './auth.store';

export type Role = 'OWNER' | 'DEVELOPER' | 'VIEWER';

function hasMinRole(current: Role, required: Role): boolean {
    const order: Record<Role, number> = { VIEWER: 0, DEVELOPER: 1, OWNER: 2 };
    return order[current] >= order[required];
}

export function usePermissions() {
    const { user } = useAuth();
    const role: Role = (user?.role || 'VIEWER') as Role;
    const emailVerified = user?.user?.status !== 'PENDING_VERIFICATION';

    return {
        role,
        emailVerified,
        isOwner: role === 'OWNER',
        isDeveloper: role === 'DEVELOPER',
        isViewer: role === 'VIEWER',

        // Projects
        canCreateProject: hasMinRole(role, 'DEVELOPER'),
        canDeleteProject: role === 'OWNER',

        // Endpoints
        canManageEndpoints: hasMinRole(role, 'DEVELOPER'),

        // Events
        canSendEvents: hasMinRole(role, 'DEVELOPER'),

        // Deliveries
        canReplayDeliveries: hasMinRole(role, 'DEVELOPER'),

        // Subscriptions
        canManageSubscriptions: hasMinRole(role, 'DEVELOPER'),

        // API Keys
        canManageApiKeys: hasMinRole(role, 'DEVELOPER'),

        // DLQ
        canManageDlq: hasMinRole(role, 'DEVELOPER'),

        // Test Endpoints
        canManageTestEndpoints: hasMinRole(role, 'DEVELOPER'),

        // Incoming Webhooks
        canManageIncomingSources: hasMinRole(role, 'DEVELOPER'),
        canReplayIncomingEvents: hasMinRole(role, 'DEVELOPER'),

        // Members
        canManageMembers: role === 'OWNER',

        // Settings
        canManageOrgSettings: role === 'OWNER',

        // PII Masking
        canManagePiiRules: hasMinRole(role, 'DEVELOPER'),

        // Debug Links
        canCreateDebugLinks: hasMinRole(role, 'DEVELOPER'),
    };
}
