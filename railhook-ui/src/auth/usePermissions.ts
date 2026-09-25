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

        canCreateProject: hasMinRole(role, 'DEVELOPER'),
        canDeleteProject: role === 'OWNER',

        canManageEndpoints: hasMinRole(role, 'DEVELOPER'),

        canSendEvents: hasMinRole(role, 'DEVELOPER'),

        canReplayDeliveries: hasMinRole(role, 'DEVELOPER'),

        canManageSubscriptions: hasMinRole(role, 'DEVELOPER'),

        canManageApiKeys: hasMinRole(role, 'DEVELOPER'),

        canManageDlq: hasMinRole(role, 'DEVELOPER'),

        canManageTestEndpoints: hasMinRole(role, 'DEVELOPER'),

        canManageIncomingSources: hasMinRole(role, 'DEVELOPER'),
        canReplayIncomingEvents: hasMinRole(role, 'DEVELOPER'),

        canManageMembers: role === 'OWNER',

        canManageOrgSettings: role === 'OWNER',

        canManagePiiRules: hasMinRole(role, 'DEVELOPER'),

        canCreateDebugLinks: hasMinRole(role, 'DEVELOPER'),
    };
}
