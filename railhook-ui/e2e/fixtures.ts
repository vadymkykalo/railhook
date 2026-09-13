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

function body(url: URL): unknown {
  const p = url.pathname;
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
