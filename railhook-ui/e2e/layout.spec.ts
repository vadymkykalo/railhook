import { expect, test, type Page } from '@playwright/test';
import { EVENT_ID, PROJECT_ID, mockApi } from './fixtures';

/**
 * Every page fits its screen.
 *
 * Seen on production: the registration form cut off at the right edge of an iPhone, because
 * iOS Safari zooms into any focused field under 16px. A page that scrolls sideways, or a field
 * small enough to trigger that zoom, is invisible to jsdom, so it is checked here in a real
 * browser at a phone width and a desktop width.
 */

const PUBLIC = ['/', '/contact', '/login', '/register', '/forgot-password', '/reset-password?token=e2e', '/verify-email?token=e2e', '/no-such-page'];
const ADMIN = [
  '/admin/dashboard',
  '/admin/projects',
  `/admin/projects/${PROJECT_ID}/endpoints`,
  `/admin/projects/${PROJECT_ID}/deliveries`,
  `/admin/projects/${PROJECT_ID}/events`,
  `/admin/projects/${PROJECT_ID}/events/${EVENT_ID}`,
  `/admin/projects/${PROJECT_ID}/api-keys`,
  `/admin/projects/${PROJECT_ID}/incoming-sources`,
  '/admin/members',
  '/admin/settings',
  '/admin/billing',
];

/** Elements that stick out past the right edge and are not inside something that scrolls or clips. */
async function overflow(page: Page) {
  return page.evaluate(() => {
    const vw = document.documentElement.clientWidth;
    const out: string[] = [];
    if (document.documentElement.scrollWidth <= vw + 1) return out;
    for (const el of Array.from(document.querySelectorAll<HTMLElement>('body *'))) {
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height || r.right <= vw + 1) continue;
      let p = el.parentElement;
      let contained = false;
      while (p && p !== document.body) {
        if (/(auto|scroll|hidden|clip)/.test(getComputedStyle(p).overflowX) && p.getBoundingClientRect().right <= vw + 1) {
          contained = true;
          break;
        }
        p = p.parentElement;
      }
      if (!contained) out.push(`<${el.tagName.toLowerCase()} class="${String(el.className).slice(0, 80)}"> right=${Math.round(r.right)}`);
    }
    return out.slice(0, 5);
  });
}

async function smallFields(page: Page) {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll<HTMLElement>('input:not([type=hidden]):not([type=checkbox]):not([type=radio]), textarea, select'))
      .filter((el) => el.getBoundingClientRect().width > 0 && parseFloat(getComputedStyle(el).fontSize) < 16)
      .map((el) => `${el.tagName.toLowerCase()}#${el.id || el.getAttribute('name') || '?'} ${getComputedStyle(el).fontSize}`),
  );
}

async function checkPage(page: Page, path: string, isMobile: boolean) {
  await page.goto(path);
  await page.waitForLoadState('networkidle');
  // Pages that animate in settle within this; the check is about resting layout.
  await page.waitForTimeout(400);

  const heading = page.locator('main h1, h1').first();
  await expect(heading, `${path}: a heading is on screen`).toBeVisible();
  const box = await heading.boundingBox();
  const vw = page.viewportSize()!.width;
  expect(box && box.x >= 0 && box.x + box.width <= vw + 1, `${path}: the heading sits inside the viewport`).toBeTruthy();

  expect(await overflow(page), `${path}: nothing sticks out past the right edge`).toEqual([]);
  if (isMobile) {
    expect(await smallFields(page), `${path}: fields are at least 16px so iOS does not zoom`).toEqual([]);
  }
}

test.describe('public and auth pages fit the screen', () => {
  for (const path of PUBLIC) {
    test(path, async ({ page, isMobile }) => {
      await mockApi(page, { signedIn: false });
      await checkPage(page, path, isMobile);
    });
  }
});

test.describe('dashboard pages fit the screen', () => {
  for (const path of ADMIN) {
    test(path, async ({ page, isMobile }) => {
      await mockApi(page, { signedIn: true });
      await checkPage(page, path, isMobile);
    });
  }

  test('the navigation opens as a drawer on a phone and fits it', async ({ page, isMobile }) => {
    test.skip(!isMobile, 'the sidebar is always open on desktop');
    await mockApi(page, { signedIn: true });
    await page.goto('/admin/dashboard');
    await page.getByRole('button', { name: /open menu/i }).click();
    const drawer = page.locator('aside').filter({ has: page.getByRole('link') }).last();
    await expect(drawer).toBeVisible();
    const box = await drawer.boundingBox();
    expect(box!.width).toBeLessThanOrEqual(page.viewportSize()!.width);
  });
});
