import { describe, it, expect } from 'vitest';

// Test the hasMinRole logic directly (extracted from usePermissions)
type Role = 'OWNER' | 'DEVELOPER' | 'VIEWER';

function hasMinRole(current: Role, required: Role): boolean {
  const order: Record<Role, number> = { VIEWER: 0, DEVELOPER: 1, OWNER: 2 };
  return order[current] >= order[required];
}

function getPermissions(role: Role) {
  return {
    role,
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
    canManageMembers: role === 'OWNER',
    canManageOrgSettings: role === 'OWNER',
  };
}

describe('RBAC permissions', () => {
  describe('OWNER role', () => {
    const perms = getPermissions('OWNER');

    it('has full access', () => {
      expect(perms.isOwner).toBe(true);
      expect(perms.canCreateProject).toBe(true);
      expect(perms.canDeleteProject).toBe(true);
      expect(perms.canManageEndpoints).toBe(true);
      expect(perms.canSendEvents).toBe(true);
      expect(perms.canReplayDeliveries).toBe(true);
      expect(perms.canManageMembers).toBe(true);
      expect(perms.canManageOrgSettings).toBe(true);
      expect(perms.canManageDlq).toBe(true);
    });
  });

  describe('DEVELOPER role', () => {
    const perms = getPermissions('DEVELOPER');

    it('can manage resources but not org settings', () => {
      expect(perms.isDeveloper).toBe(true);
      expect(perms.canCreateProject).toBe(true);
      expect(perms.canManageEndpoints).toBe(true);
      expect(perms.canSendEvents).toBe(true);
      expect(perms.canReplayDeliveries).toBe(true);
      expect(perms.canManageApiKeys).toBe(true);
      expect(perms.canManageDlq).toBe(true);
      expect(perms.canManageTestEndpoints).toBe(true);
    });

    it('cannot manage members or delete projects', () => {
      expect(perms.canDeleteProject).toBe(false);
      expect(perms.canManageMembers).toBe(false);
      expect(perms.canManageOrgSettings).toBe(false);
    });
  });

  describe('VIEWER role', () => {
    const perms = getPermissions('VIEWER');

    it('is read-only', () => {
      expect(perms.isViewer).toBe(true);
      expect(perms.canCreateProject).toBe(false);
      expect(perms.canDeleteProject).toBe(false);
      expect(perms.canManageEndpoints).toBe(false);
      expect(perms.canSendEvents).toBe(false);
      expect(perms.canReplayDeliveries).toBe(false);
      expect(perms.canManageApiKeys).toBe(false);
      expect(perms.canManageMembers).toBe(false);
      expect(perms.canManageOrgSettings).toBe(false);
      expect(perms.canManageDlq).toBe(false);
    });
  });

  describe('hasMinRole', () => {
    it('OWNER >= all roles', () => {
      expect(hasMinRole('OWNER', 'VIEWER')).toBe(true);
      expect(hasMinRole('OWNER', 'DEVELOPER')).toBe(true);
      expect(hasMinRole('OWNER', 'OWNER')).toBe(true);
    });

    it('DEVELOPER >= VIEWER and DEVELOPER', () => {
      expect(hasMinRole('DEVELOPER', 'VIEWER')).toBe(true);
      expect(hasMinRole('DEVELOPER', 'DEVELOPER')).toBe(true);
      expect(hasMinRole('DEVELOPER', 'OWNER')).toBe(false);
    });

    it('VIEWER only >= VIEWER', () => {
      expect(hasMinRole('VIEWER', 'VIEWER')).toBe(true);
      expect(hasMinRole('VIEWER', 'DEVELOPER')).toBe(false);
      expect(hasMinRole('VIEWER', 'OWNER')).toBe(false);
    });
  });
});
