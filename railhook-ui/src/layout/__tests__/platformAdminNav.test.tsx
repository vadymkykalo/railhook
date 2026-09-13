import { describe, it, expect, vi } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type { CurrentUserResponse } from '../../types/api.types';

vi.mock('../../components/ProjectSwitcher', () => ({ default: () => null }));
vi.mock('../../components/OrganizationSwitcher', () => ({ default: () => null }));

import Sidebar from '../Sidebar';
import { PLATFORM_SECTION, requiredRoleFor, sectionFor, segmentOf } from '../nav.config';

function userWith(platformAdmin: boolean): CurrentUserResponse {
  return {
    user: { id: 'user-1', email: 'owner@example.com', fullName: null, status: 'ACTIVE' },
    organization: { id: 'org-1', name: 'Test Org', createdAt: new Date().toISOString() },
    role: 'OWNER',
    emailDeliveryEnabled: false,
    hasPassword: true,
    platformAdmin,
  };
}

function renderSidebar(platformAdmin: boolean, at = '/admin/dashboard') {
  return renderPage(
    <Sidebar role="OWNER" user={userWith(platformAdmin)} collapsed={false} onToggleCollapsed={() => {}} onLogout={() => {}} />,
    { path: '/admin/*', initialEntry: at },
  );
}

describe('the platform admin entry', () => {
  it('is offered to a platform admin', () => {
    renderSidebar(true);
    expect(screen.getByRole('link', { name: 'Platform admin' })).toHaveAttribute('href', '/admin/platform');
  });

  it('is not offered to an organization owner who is not one', () => {
    // OWNER is the most privileged tenant role, and still not the deployment's operator.
    renderSidebar(false);
    expect(screen.queryByRole('link', { name: 'Platform admin' })).not.toBeInTheDocument();
  });

  it('is marked current anywhere inside the panel', () => {
    renderSidebar(true, '/admin/platform/organizations/org-9');
    expect(screen.getByRole('link', { name: 'Platform admin' })).toHaveAttribute('aria-current', 'page');
  });
});

describe('the platform admin section', () => {
  it('names each view by its own segment, so only one tab is current', () => {
    expect(segmentOf('/admin/platform')).toBe('platform');
    expect(segmentOf('/admin/platform/organizations')).toBe('platform-organizations');
    expect(segmentOf('/admin/platform/organizations/abc')).toBe('platform-organizations');
    expect(segmentOf('/admin/platform/users')).toBe('platform-users');

    const current = PLATFORM_SECTION.tabs.filter((tab) => tab.owns.includes(segmentOf('/admin/platform/users')));
    expect(current.map((tab) => tab.nameKey)).toEqual(['nav.platformUsers']);
  });

  it('owns every panel route, and demands no organization role', () => {
    for (const path of ['/admin/platform', '/admin/platform/organizations', '/admin/platform/organizations/x', '/admin/platform/users']) {
      expect(sectionFor(path)).toBe(PLATFORM_SECTION);
      expect(requiredRoleFor(path)).toBeUndefined();
    }
  });
});
