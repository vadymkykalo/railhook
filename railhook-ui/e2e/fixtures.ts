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
};

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

function body(url: URL): unknown {
  const p = url.pathname;
  if (p.endsWith('/endpoints')) return page0(ENDPOINTS);
  if (p.endsWith('/events')) return page0(EVENTS);
  if (p.endsWith('/deliveries') || p.endsWith(`/deliveries/projects/${PROJECT_ID}`)) return page0(DELIVERIES);
  if (p.endsWith('/auth/refresh')) return { accessToken: 'e2e-token', tokenType: 'Bearer', expiresIn: 3600 };
  if (p.endsWith('/auth/me')) return USER;
  if (p.endsWith('/auth/providers')) return { google: false };
  if (/\/projects$/.test(p)) return [PROJECT];
  if (new RegExp(`/projects/${PROJECT_ID}$`).test(p)) return PROJECT;
  if (p.endsWith('/billing/plans')) return [];
  if (/\/(events|deliveries|endpoints|subscriptions|api-keys|incoming-sources|incoming-events|members|audit-log)$/.test(p)) {
    return url.searchParams.has('page') || url.searchParams.has('size') ? page0() : [];
  }
  return {};
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
