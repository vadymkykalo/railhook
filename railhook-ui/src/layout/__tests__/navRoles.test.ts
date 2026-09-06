import { describe, it, expect } from 'vitest';
import { SETTINGS_SECTION, PROJECT_SECTIONS, requiredRoleFor } from '../nav.config';
import { hasMinRole, type Role } from '../../auth/ProtectedRoute';

const ROLES: Role[] = ['VIEWER', 'DEVELOPER', 'OWNER'];

/**
 * The sidebar decided who saw a link and the router decided who could open it,
 * and the two were written out separately. `/admin/settings` — the personal
 * profile page, where a password is changed — ended up shown to everyone and
 * guarded at OWNER, so an invited developer could see the entry, click it, and
 * be told access was denied to their own account. Both sides now read
 * `requiredRoleFor`, and these are the facts that keeps.
 */
describe('route roles', () => {
  it('lets any authenticated member reach their own profile', () => {
    expect(requiredRoleFor('/admin/settings')).toBeUndefined();
  });

  it('keeps the organization-level pages owner-only', () => {
    expect(requiredRoleFor('/admin/org-settings')).toBe('OWNER');
    expect(requiredRoleFor('/admin/members')).toBe('OWNER');
    expect(requiredRoleFor('/admin/billing')).toBe('OWNER');
  });

  it('demands nothing of a path no nav entry claims', () => {
    expect(requiredRoleFor('/admin/dashboard')).toBeUndefined();
    expect(requiredRoleFor('/admin/nothing-here')).toBeUndefined();
  });

  it('offers a settings tab exactly where the guard would let the member through', () => {
    for (const role of ROLES) {
      for (const tab of SETTINGS_SECTION.tabs) {
        const offered = !tab.requiredRole || hasMinRole(role, tab.requiredRole);
        const required = requiredRoleFor(tab.path());
        const admitted = !required || hasMinRole(role, required);
        expect(`${role} ${tab.nameKey} offered=${offered}`)
          .toBe(`${role} ${tab.nameKey} offered=${admitted}`);
      }
    }
  });

  it('offers a project tab exactly where the guard would let the member through', () => {
    const entries = PROJECT_SECTIONS.flatMap((section) => [section, ...section.tabs]);
    for (const role of ROLES) {
      for (const entry of entries) {
        const offered = !entry.requiredRole || hasMinRole(role, entry.requiredRole);
        const required = requiredRoleFor(entry.path('project-1'));
        const admitted = !required || hasMinRole(role, required);
        expect(`${role} ${entry.nameKey} offered=${offered}`)
          .toBe(`${role} ${entry.nameKey} offered=${admitted}`);
      }
    }
  });
});
