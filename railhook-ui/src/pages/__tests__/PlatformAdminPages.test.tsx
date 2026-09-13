import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type { CurrentUserResponse } from '../../types/api.types';
import type { AdminOrganization, PlatformOverview } from '../../api/platformAdmin.api';

vi.mock('../../api/platformAdmin.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/platformAdmin.api')>()),
  platformAdminApi: {
    overview: vi.fn(),
    organizations: vi.fn(),
    organization: vi.fn(),
    usage: vi.fn(),
    members: vi.fn(),
    projects: vi.fn(),
    auditLog: vi.fn(),
    suspend: vi.fn(),
    reinstate: vi.fn(),
    users: vi.fn(),
  },
}));

import PlatformAdminGate from '../../components/PlatformAdminGate';
import PlatformOverviewPage from '../PlatformOverviewPage';
import PlatformOrganizationsPage from '../PlatformOrganizationsPage';
import PlatformOrganizationDetailPage from '../PlatformOrganizationDetailPage';
import PlatformUsersPage from '../PlatformUsersPage';
import { platformAdminApi } from '../../api/platformAdmin.api';

const page = <T,>(content: T[]) => ({
  content, totalElements: content.length, totalPages: 1, size: 20, number: 0, first: true, last: true,
});

const ORG: AdminOrganization = {
  id: 'org-9',
  name: 'Acme Corp',
  planName: 'free',
  billingStatus: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
  ownerEmail: 'owner@acme.example',
  projectCount: 2,
  memberCount: 3,
  eventsThisMonth: 9000,
  eventsLimit: 10000,
  suspendedAt: null,
  suspensionReason: null,
  suspendedBy: null,
};

const resource = { current: 1, limit: 3, percentUsed: 33.3 };

function adminUser(platformAdmin: boolean): CurrentUserResponse {
  return {
    user: { id: 'user-1', email: 'ops@example.com', fullName: null, status: 'ACTIVE' },
    organization: { id: 'org-1', name: 'Ops', createdAt: '2026-09-01T00:00:00Z' },
    role: 'OWNER',
    emailDeliveryEnabled: false,
    hasPassword: true,
    platformAdmin,
  };
}

describe('PlatformAdminGate', () => {
  it('refuses an organization owner who is not a platform admin', () => {
    renderPage(<PlatformAdminGate><p>secret panel</p></PlatformAdminGate>, {
      path: '/admin/platform', initialEntry: '/admin/platform', auth: { user: adminUser(false) },
    });
    expect(screen.getByRole('heading', { name: 'Not a platform admin' })).toBeInTheDocument();
    expect(screen.queryByText('secret panel')).not.toBeInTheDocument();
  });

  it('lets a platform admin through', () => {
    renderPage(<PlatformAdminGate><p>secret panel</p></PlatformAdminGate>, {
      path: '/admin/platform', initialEntry: '/admin/platform', auth: { user: adminUser(true) },
    });
    expect(screen.getByText('secret panel')).toBeInTheDocument();
  });
});

describe('PlatformOverviewPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows the deployment totals and the latest sign-ups', async () => {
    const overview: PlatformOverview = {
      organizations: 128, suspendedOrganizations: 3, users: 342, signupsToday: 4, signups7d: 31, signups30d: 97,
      eventsToday: 1000, events30d: 50000, deliveriesSucceeded24h: 900, deliveriesFailed24h: 12,
      activeTunnels: 2, organizationsNearQuota: 5, generatedAt: '2026-09-13T12:00:00Z',
      recentSignups: [{
        userId: 'u-1', email: 'new@customer.example', fullName: null, emailVerified: false, status: 'PENDING_VERIFICATION',
        signInMethods: ['GOOGLE'], organizationId: 'org-9', organizationName: 'Acme Corp', createdAt: '2026-09-13T11:00:00Z',
      }],
    };
    vi.mocked(platformAdminApi.overview).mockResolvedValue(overview);

    renderPage(<PlatformOverviewPage />, { path: '/admin/platform', initialEntry: '/admin/platform' });

    expect(await screen.findByText('new@customer.example')).toBeInTheDocument();
    expect(screen.getByText('128')).toBeInTheDocument();
    expect(screen.getByText('Near event limit')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Acme Corp' })).toHaveAttribute('href', '/admin/platform/organizations/org-9');
  });

  it('asks for a fresh sign-in instead of offering a retry that would fail the same way', async () => {
    vi.mocked(platformAdminApi.overview).mockRejectedValue({
      response: { status: 403, data: { error: 'reauthentication_required' } },
    });

    renderPage(<PlatformOverviewPage />, { path: '/admin/platform', initialEntry: '/admin/platform' });

    expect(await screen.findByRole('button', { name: /Sign in again/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Retry/ })).not.toBeInTheDocument();
  });
});

describe('PlatformOrganizationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(platformAdminApi.organizations).mockResolvedValue(page([ORG]));
  });

  it('lists each organization with its owner and a link to it', async () => {
    renderPage(<PlatformOrganizationsPage />, {
      path: '/admin/platform/organizations', initialEntry: '/admin/platform/organizations',
    });

    expect(await screen.findByRole('link', { name: 'Acme Corp' })).toHaveAttribute('href', '/admin/platform/organizations/org-9');
    expect(screen.getByText('owner@acme.example')).toBeInTheDocument();
  });

  it('searches by what was typed once typing stops', async () => {
    renderPage(<PlatformOrganizationsPage />, {
      path: '/admin/platform/organizations', initialEntry: '/admin/platform/organizations',
    });
    await screen.findByRole('link', { name: 'Acme Corp' });

    await userEvent.type(screen.getByRole('searchbox', { name: /Search by name or member email/ }), 'owner@acme');

    await waitFor(() => expect(platformAdminApi.organizations)
      .toHaveBeenLastCalledWith(0, 20, { search: 'owner@acme', suspendedOnly: false }));
  });
});

describe('PlatformOrganizationDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(platformAdminApi.organization).mockResolvedValue(ORG);
    vi.mocked(platformAdminApi.usage).mockResolvedValue({
      events: resource, endpoints: resource, projects: resource, members: resource,
      rateLimitPerSecond: 10, retentionDays: 7, periodStart: '2026-09-01T00:00:00Z', periodEnd: '2026-10-01T00:00:00Z',
    });
    vi.mocked(platformAdminApi.members).mockResolvedValue(page([{
      userId: 'u-1', email: 'owner@acme.example', fullName: 'Ada', role: 'OWNER', membershipStatus: 'ACTIVE',
      emailVerified: true, userStatus: 'ACTIVE', signInMethods: ['PASSWORD', 'GOOGLE'],
      joinedAt: '2026-09-01T00:00:00Z', lastSeenAt: null,
    }]));
    vi.mocked(platformAdminApi.projects).mockResolvedValue(page([{ id: 'p-1', name: 'Checkout', createdAt: '2026-09-01T00:00:00Z' }]));
    vi.mocked(platformAdminApi.auditLog).mockResolvedValue(page([]));
    vi.mocked(platformAdminApi.suspend).mockResolvedValue({ ...ORG, suspendedAt: '2026-09-13T12:00:00Z' });
  });

  function renderDetail() {
    return renderPage(<PlatformOrganizationDetailPage />, {
      path: '/admin/platform/organizations/:organizationId',
      initialEntry: '/admin/platform/organizations/org-9',
    });
  }

  it('shows members with their role and how they sign in', async () => {
    renderDetail();
    expect(await screen.findByRole('heading', { name: 'Acme Corp' })).toBeInTheDocument();
    expect(await screen.findByText('Ada')).toBeInTheDocument();
    expect(screen.getAllByText('Google').length).toBeGreaterThan(0);
    expect(screen.getByText('Checkout')).toBeInTheDocument();
  });

  it('suspends only once the name is typed back and a reason is given', async () => {
    const user = userEvent.setup();
    renderDetail();
    await user.click(await screen.findByRole('button', { name: 'Suspend' }));

    const dialog = await screen.findByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Suspend organization' });
    expect(confirm).toBeDisabled();

    await user.type(within(dialog).getByLabelText('Type Acme Corp to confirm'), 'Acme Corp');
    expect(confirm).toBeDisabled();

    await user.type(within(dialog).getByLabelText('Reason'), 'Phishing endpoints');
    expect(confirm).toBeEnabled();

    await user.click(confirm);
    await waitFor(() => expect(platformAdminApi.suspend).toHaveBeenCalledWith('org-9', 'Phishing endpoints'));
  });
});

describe('PlatformUsersPage', () => {
  it('lists accounts with verification and their organizations', async () => {
    vi.mocked(platformAdminApi.users).mockResolvedValue(page([{
      id: 'u-1', email: 'ada@acme.example', fullName: 'Ada', emailVerified: false, status: 'PENDING_VERIFICATION',
      signInMethods: ['PASSWORD'], organizations: [{ id: 'org-9', name: 'Acme Corp', role: 'OWNER' }],
      createdAt: '2026-09-01T00:00:00Z', lastSeenAt: null,
    }]));

    renderPage(<PlatformUsersPage />, { path: '/admin/platform/users', initialEntry: '/admin/platform/users' });

    expect(await screen.findByText('ada@acme.example')).toBeInTheDocument();
    expect(screen.getByText('Not verified')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Acme Corp' })).toHaveAttribute('href', '/admin/platform/organizations/org-9');
  });
});
