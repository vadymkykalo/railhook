import type { Page, Route } from '@playwright/test';

/**
 * The API, answered in the browser, so the layout checks run against the production bundle with
 * no backend. Shapes are the minimum each page reads; anything unlisted gets an empty success,
 * which is what a fresh organization looks like.
 */
export const PROJECT_ID = '00000000-0000-4000-8000-000000000001';
export const EVENT_ID = '00000000-0000-4000-8000-000000000002';

const USER = {
  user: {
    id: '00000000-0000-4000-8000-0000000000aa',
    email: 'layout-check@example.com',
    fullName: 'Layout Check',
    status: 'ACTIVE',
    emailVerified: true,
  },
  organization: { id: '00000000-0000-4000-8000-0000000000bb', name: 'Example Organization With A Long Name', createdAt: '2026-09-01T00:00:00Z' },
  organizations: [],
  role: 'OWNER',
  platformAdmin: true,
};

export const PLATFORM_ORG_ID = '00000000-0000-4000-8000-0000000000cc';

const PROJECT = {
  id: PROJECT_ID,
  name: 'Checkout webhooks for a very long project name',
  description: 'Orders, refunds and subscription events',
  createdAt: '2026-09-01T00:00:00Z',
};

const page0 = (content: unknown[] = []) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 20 });

/**
 * Records with the lengths that break a phone layout: endpoint URLs a customer really registers,
 * dotted event types, a page of rows rather than one. An empty list lays out trivially and hid
 * that every list table was a 650–1060px desktop table swiped sideways at 390px.
 */
const at = (minutesAgo: number) => new Date(Date.UTC(2026, 8, 13, 12, 0) - minutesAgo * 60_000).toISOString();
const ENDPOINTS = Array.from({ length: 6 }, (_, i) => ({
  id: `00000000-0000-4000-8000-00000000e${String(i).padStart(3, '0')}`,
  projectId: PROJECT_ID,
  url: `https://hooks.northwind-logistics.example.com/integrations/railhook/v2/customers/${1000 + i}/webhooks`,
  description: 'Warehouse fulfilment notifications for the EU region',
  enabled: i !== 3,
  verificationStatus: i % 2 ? 'VERIFIED' : 'PENDING',
  createdAt: at(600 + i),
  updatedAt: at(500 + i),
}));
const STATUSES = ['SUCCESS', 'FAILED', 'PENDING', 'DLQ', 'SUCCESS'] as const;
const EVENTS = Array.from({ length: 20 }, (_, i) => ({
  id: `00000000-0000-4000-8000-0000000${String(10000 + i)}`,
  projectId: PROJECT_ID,
  eventType: `order.fulfilment.shipment_${i % 2 ? 'dispatched' : 'label_created'}`,
  payload: '{"orderId":"ord_48350","total":3230}',
  createdAt: at(i * 7),
  deliveriesCreated: 2,
}));
const DELIVERIES = EVENTS.map((event, i) => ({
  id: `00000000-0000-4000-8000-0000000${String(20000 + i)}`,
  eventId: event.id,
  endpointId: ENDPOINTS[i % ENDPOINTS.length].id,
  subscriptionId: '00000000-0000-4000-8000-000000000s01',
  status: STATUSES[i % STATUSES.length],
  attemptCount: (i % 4) + 1,
  maxAttempts: 8,
  nextRetryAt: STATUSES[i % STATUSES.length] === 'PENDING' ? at(-5) : undefined,
  createdAt: event.createdAt,
}));

/** The platform admin panel, with the lengths a real deployment has: long names, long addresses. */
const PLATFORM_ORGS = Array.from({ length: 6 }, (_, i) => ({
  id: i === 0 ? PLATFORM_ORG_ID : `00000000-0000-4000-8000-0000000001${String(i).padStart(2, '0')}`,
  name: `Northwind Logistics International Holdings ${i + 1}`,
  planName: 'free',
  billingStatus: 'ACTIVE',
  createdAt: at(60 * 24 * (i + 1)),
  ownerEmail: `platform-operations.team+region-${i}@northwind-logistics.example.com`,
  projectCount: i + 1,
  memberCount: 2 * i + 1,
  eventsThisMonth: 850 * (i + 1),
  eventsLimit: 10000,
  suspendedAt: i === 2 ? at(90) : null,
  suspensionReason: i === 2 ? 'Phishing endpoints reported by three recipients' : null,
  suspendedBy: i === 2 ? 'ops@example.com' : null,
}));
const PLATFORM_ACCOUNTS = Array.from({ length: 8 }, (_, i) => ({
  id: `00000000-0000-4000-8000-0000000002${String(i).padStart(2, '0')}`,
  userId: `00000000-0000-4000-8000-0000000002${String(i).padStart(2, '0')}`,
  email: `platform-operations.team+region-${i}@northwind-logistics.example.com`,
  fullName: 'Alexandra Konstantinopolska-Whitfield',
  emailVerified: i % 3 !== 0,
  status: i % 3 === 0 ? 'PENDING_VERIFICATION' : 'ACTIVE',
  userStatus: 'ACTIVE',
  signInMethods: i % 2 ? ['PASSWORD', 'GOOGLE'] : ['PASSWORD'],
  organizations: [{ id: PLATFORM_ORG_ID, name: PLATFORM_ORGS[0].name, role: 'OWNER' }],
  organizationId: PLATFORM_ORG_ID,
  organizationName: PLATFORM_ORGS[0].name,
  role: i === 0 ? 'OWNER' : 'DEVELOPER',
  membershipStatus: 'ACTIVE',
  joinedAt: at(600),
  createdAt: at(30 * (i + 1)),
  lastSeenAt: at(5 * (i + 1)),
}));
const resource = (current: number, limit: number) => ({ current, limit, percentUsed: Math.round((current / limit) * 1000) / 10 });

function body(url: URL): unknown {
  const p = url.pathname;
  if (p.endsWith('/endpoints')) return page0(ENDPOINTS);
  if (p.endsWith('/events')) return page0(EVENTS);
  if (p.endsWith('/deliveries') || p.endsWith(`/deliveries/projects/${PROJECT_ID}`)) return page0(DELIVERIES);
  if (p.endsWith('/auth/refresh')) return { accessToken: 'e2e-token', tokenType: 'Bearer', expiresIn: 3600 };
  if (p.endsWith('/auth/me')) return USER;
  if (p.endsWith('/auth/providers')) return { google: false };
  if (/\/projects$/.test(p) && !p.includes('/admin/organizations/')) return [PROJECT];
  if (new RegExp(`/projects/${PROJECT_ID}$`).test(p)) return PROJECT;
  if (p.endsWith('/admin/overview')) {
    return {
      organizations: 128, suspendedOrganizations: 3, users: 342, signupsToday: 4, signups7d: 31, signups30d: 97,
      eventsToday: 184320, events30d: 4812004, deliveriesSucceeded24h: 351200, deliveriesFailed24h: 1840,
      activeTunnels: 12, organizationsNearQuota: 5, recentSignups: PLATFORM_ACCOUNTS, generatedAt: at(0),
    };
  }
  if (p.endsWith('/admin/organizations')) return page0(PLATFORM_ORGS);
  if (p.endsWith(`/admin/organizations/${PLATFORM_ORG_ID}`)) return PLATFORM_ORGS[0];
  if (p.endsWith('/usage') && p.includes('/admin/organizations/')) {
    return {
      events: resource(8500, 10000), endpoints: resource(3, 10), projects: resource(1, 3), members: resource(5, 5),
      rateLimitPerSecond: 50, retentionDays: 7, periodStart: at(60 * 24 * 12), periodEnd: at(-60 * 24 * 18),
    };
  }
  if (p.endsWith('/members') && p.includes('/admin/organizations/')) return page0(PLATFORM_ACCOUNTS);
  if (p.endsWith('/projects') && p.includes('/admin/organizations/')) return page0([PROJECT]);
  if (p.endsWith('/audit-log') && p.includes('/admin/organizations/')) {
    return page0(Array.from({ length: 6 }, (_, i) => ({
      id: `00000000-0000-4000-8000-0000000003${String(i).padStart(2, '0')}`,
      action: i % 2 ? 'UPDATE' : 'ORGANIZATION_SUSPENDED',
      resourceType: 'Endpoint',
      resourceId: ENDPOINTS[0].id,
      actorEmail: PLATFORM_ACCOUNTS[i].email,
      status: i === 3 ? 'FAILURE' : 'SUCCESS',
      clientIp: '203.0.113.42',
      createdAt: at(i * 11),
    })));
  }
  if (p.endsWith('/admin/users')) return page0(PLATFORM_ACCOUNTS);
  if (p.endsWith('/billing/plans')) return [];
  if (/\/(events|deliveries|endpoints|subscriptions|api-keys|incoming-sources|incoming-events|members|audit-log)$/.test(p)) {
    return url.searchParams.has('page') || url.searchParams.has('size') ? page0() : [];
  }
  return {};
}

/**
 * A brand-new organization: no project until the create call, then the one it made. The list
 * answers from state so the dashboard's refetch after creating sees the new project, the way the
 * real API would.
 */
export async function mockNewOrganization(page: Page) {
  const created = { id: PROJECT_ID, name: 'Checkout', description: '', createdAt: '2026-09-13T12:00:00Z' };
  let projects: unknown[] = [];
  await page.addInitScript((user) => {
    localStorage.setItem('auth_user', JSON.stringify(user));
    localStorage.setItem('i18n_lng', 'en');
  }, USER);
  await page.route('**/api/v1/**', (route: Route) => {
    const url = new URL(route.request().url());
    const json = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
    if (/\/projects$/.test(url.pathname)) {
      if (route.request().method() === 'POST') {
        projects = [created];
        return json(created);
      }
      return json(projects);
    }
    if (new RegExp(`/projects/${PROJECT_ID}$`).test(url.pathname)) return json(created);
    if (/\/(events|deliveries|endpoints)$/.test(url.pathname)) return json(page0());
    return json(body(url));
  });
}

export async function mockApi(page: Page, { signedIn }: { signedIn: boolean }) {
  if (signedIn) {
    await page.addInitScript((user) => {
      localStorage.setItem('auth_user', JSON.stringify(user));
      localStorage.setItem('i18n_lng', 'en');
    }, USER);
  } else {
    await page.addInitScript(() => localStorage.setItem('i18n_lng', 'en'));
  }
  await page.route('**/api/v1/**', (route: Route) => {
    const url = new URL(route.request().url());
    if (!signedIn && url.pathname.endsWith('/auth/refresh')) {
      return route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"unauthorized"}' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body(url)) });
  });
}
