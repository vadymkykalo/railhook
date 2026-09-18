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

const DAYS = Array.from({ length: 30 }, (_, i) => ({
  date: `2026-08-${String(i + 1).padStart(2, '0')}`, signups: i % 3, events: i * 10,
}));
const ACTIVATION = { signups: 40, verified: 30, organizations: 20, withProject: 10, withEvent: 5 };

describe('PlatformOverviewPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows how far the last month\u2019s sign-ups got, each step against the one before it', async () => {
    vi.mocked(platformAdminApi.overview).mockResolvedValue({
      organizations: 1, suspendedOrganizations: 0, users: 1, signupsToday: 0, signups7d: 0, signups30d: 40,
      eventsToday: 0, events30d: 0, deliveriesSucceeded24h: 0, deliveriesFailed24h: 0,
      activeTunnels: 0, organizationsNearQuota: 0, generatedAt: '2026-09-13T12:00:00Z', recentSignups: [],
      daily30d: DAYS, activation30d: ACTIVATION,
    });
    renderPage(<PlatformOverviewPage />, { path: '/admin/platform', initialEntry: '/admin/platform' });

    const funnel = await screen.findByRole('list', { name: 'Activation, last 30 days' });
    const steps = within(funnel).getAllByRole('listitem').map((li) => li.textContent);
    expect(steps).toEqual([
      expect.stringMatching(/Signed up.*40/),
      expect.stringMatching(/Verified their address.*30.*75%/),
      expect.stringMatching(/New organizations.*20/),
      expect.stringMatching(/Created a project.*10.*50%/),
      expect.stringMatching(/Sent an event.*5.*50%/),
    ]);
    expect(screen.getByText('Sign-ups per day')).toBeInTheDocument();
    expect(screen.getByText('Events per day')).toBeInTheDocument();
  });

  it('shows the deployment totals and the latest sign-ups', async () => {
    const overview: PlatformOverview = {
      organizations: 128, suspendedOrganizations: 3, users: 342, signupsToday: 4, signups7d: 31, signups30d: 97,
      eventsToday: 1000, events30d: 50000, deliveriesSucceeded24h: 900, deliveriesFailed24h: 12,
      activeTunnels: 2, organizationsNearQuota: 5, generatedAt: '2026-09-13T12:00:00Z',
      daily30d: DAYS, activation30d: ACTIVATION,
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

  it('says who sees the panel and the one thing it can change', async () => {
    vi.mocked(platformAdminApi.overview).mockResolvedValue({
      organizations: 1, suspendedOrganizations: 0, users: 1, signupsToday: 0, signups7d: 0, signups30d: 0,
      eventsToday: 0, events30d: 0, deliveriesSucceeded24h: 0, deliveriesFailed24h: 0,
      activeTunnels: 0, organizationsNearQuota: 0, generatedAt: '2026-09-13T12:00:00Z', recentSignups: [],
      daily30d: DAYS, activation30d: { signups: 0, verified: 0, organizations: 0, withProject: 0, withEvent: 0 },
    });
    renderPage(<PlatformOverviewPage />, { path: '/admin/platform', initialEntry: '/admin/platform' });
    expect(await screen.findByText(/Only accounts listed in PLATFORM_ADMIN_EMAILS/)).toBeInTheDocument();
    expect(screen.getByText(/suspending or reinstating an organization/)).toBeInTheDocument();
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

const LONG_EMAIL = 'olena.shulha.operations-escalations-team@railhook-enterprise-customers-europe.example.com';
const LONG_ORG = 'Товариство з обмеженою відповідальністю «Київські цифрові платіжні сервіси та інтеграції»';

/**
 * A long address is cut with an ellipsis, never broken mid-word, and the whole of it is one hover
 * away; a long name keeps its full text for the same reason.
 */
function expectShortenedWithFullValue(element: HTMLElement, full: string) {
  expect(element).toHaveAttribute('title', full);
  expect(element).toHaveClass('truncate');
  expect(element).not.toHaveClass('break-all');
}

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

  it('shortens a long owner address and a long name without losing either', async () => {
    vi.mocked(platformAdminApi.organizations).mockResolvedValue(page([{ ...ORG, name: LONG_ORG, ownerEmail: LONG_EMAIL }]));
    renderPage(<PlatformOrganizationsPage />, {
      path: '/admin/platform/organizations', initialEntry: '/admin/platform/organizations',
    });

    expectShortenedWithFullValue(await screen.findByText(LONG_EMAIL), LONG_EMAIL);
    expect(screen.getByRole('link', { name: LONG_ORG })).toHaveAttribute('title', LONG_ORG);
  });

  it('shows an unlimited plan as Unlimited, not a fraction of nothing', async () => {
    vi.mocked(platformAdminApi.organizations).mockResolvedValue(page([
      { ...ORG, planName: 'self_hosted', eventsThisMonth: 42, eventsLimit: -1 },
    ]));
    renderPage(<PlatformOrganizationsPage />, {
      path: '/admin/platform/organizations', initialEntry: '/admin/platform/organizations',
    });
    expect(await screen.findByText('Unlimited')).toBeInTheDocument();
    expect(screen.queryByText(/∞/)).not.toBeInTheDocument();
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
      joinedAt: '2026-09-01T00:00:00Z', lastSeenAt: null, platformAdmin: false,
    }, {
      userId: 'u-2', email: 'ops@acme.example', fullName: null, role: 'OWNER', membershipStatus: 'ACTIVE',
      emailVerified: true, userStatus: 'ACTIVE', signInMethods: ['GOOGLE'],
      joinedAt: '2026-09-02T00:00:00Z', lastSeenAt: null, platformAdmin: true,
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

  it('shows the owner address in the header whole on hover, never broken mid-word', async () => {
    vi.mocked(platformAdminApi.organization).mockResolvedValue({ ...ORG, ownerEmail: LONG_EMAIL });
    renderDetail();
    expectShortenedWithFullValue(await screen.findByText(LONG_EMAIL), LONG_EMAIL);
  });

  it('marks the member who is a platform admin, and only that one', async () => {
    renderDetail();
    const operator = (await screen.findByText('ops@acme.example')).closest('tr')!;
    expect(within(operator).getByText('Platform admin')).toBeInTheDocument();
    const owner = screen.getAllByText('owner@acme.example').map((el) => el.closest('tr')).find(Boolean)!;
    expect(within(owner).queryByText('Platform admin')).not.toBeInTheDocument();
  });

  it('shows an unlimited plan as Unlimited', async () => {
    const unlimited = { current: 5, limit: -1, percentUsed: 0 };
    vi.mocked(platformAdminApi.usage).mockResolvedValue({
      events: unlimited, endpoints: unlimited, projects: unlimited, members: unlimited,
      rateLimitPerSecond: -1, retentionDays: -1, periodStart: '2026-09-01T00:00:00Z', periodEnd: '2026-10-01T00:00:00Z',
    });
    renderDetail();
    expect((await screen.findAllByText(/Unlimited/)).length).toBe(4);
    expect(screen.queryByText(/∞/)).not.toBeInTheDocument();
  });

  it('centres the empty projects and audit log inside their cards', async () => {
    vi.mocked(platformAdminApi.projects).mockResolvedValue(page([]));
    renderDetail();
    for (const text of ['No projects.', 'Nothing recorded yet.']) {
      const container = (await screen.findByText(text)).parentElement!;
      expect(container).toHaveClass('flex', 'items-center', 'justify-center');
    }
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
  beforeEach(() => {
    vi.mocked(platformAdminApi.users).mockResolvedValue(page([{
      id: 'u-1', email: 'ada@acme.example', fullName: 'Ada', emailVerified: false, status: 'PENDING_VERIFICATION',
      signInMethods: ['PASSWORD'], organizations: [{ id: 'org-9', name: 'Acme Corp', role: 'OWNER' }],
      createdAt: '2026-09-01T00:00:00Z', lastSeenAt: null, platformAdmin: false,
    }, {
      id: 'u-2', email: 'ops@example.com', fullName: 'Operator', emailVerified: true, status: 'ACTIVE',
      signInMethods: ['GOOGLE'], organizations: [{ id: 'org-1', name: 'Ops', role: 'OWNER' }],
      createdAt: '2026-09-01T00:00:00Z', lastSeenAt: null, platformAdmin: true,
    }]));
  });

  it('lists accounts with verification and their organizations', async () => {
    renderPage(<PlatformUsersPage />, { path: '/admin/platform/users', initialEntry: '/admin/platform/users' });

    expect(await screen.findByText('ada@acme.example')).toBeInTheDocument();
    expect(screen.getByText('Not verified')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Acme Corp' })).toHaveAttribute('href', '/admin/platform/organizations/org-9');
  });

  it('shortens a long address and a long organization name without losing either', async () => {
    vi.mocked(platformAdminApi.users).mockResolvedValue(page([{
      id: 'u-3', email: LONG_EMAIL, fullName: null, emailVerified: true, status: 'ACTIVE',
      signInMethods: ['PASSWORD'], organizations: [{ id: 'org-7', name: LONG_ORG, role: 'OWNER' }],
      createdAt: '2026-09-01T00:00:00Z', lastSeenAt: null, platformAdmin: false,
    }]));
    renderPage(<PlatformUsersPage />, { path: '/admin/platform/users', initialEntry: '/admin/platform/users' });

    expectShortenedWithFullValue(await screen.findByText(LONG_EMAIL), LONG_EMAIL);
    expect(screen.getByRole('link', { name: LONG_ORG })).toHaveAttribute('title', LONG_ORG);
  });

  it('marks the accounts that are platform admins, and no other', async () => {
    renderPage(<PlatformUsersPage />, { path: '/admin/platform/users', initialEntry: '/admin/platform/users' });

    const operator = (await screen.findByText('ops@example.com')).closest('tr')!;
    expect(within(operator).getByText('Platform admin')).toBeInTheDocument();
    const customer = screen.getByText('ada@acme.example').closest('tr')!;
    expect(within(customer).queryByText('Platform admin')).not.toBeInTheDocument();
  });
});
