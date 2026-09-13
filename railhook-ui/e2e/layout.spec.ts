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

  // The page's own title: an h1, or the h2 PageHeader draws on dashboard pages whose layout has no
  // section heading (/admin/projects).
  const heading = page.locator('h1, main h2').first();
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

test.describe('the landing page on a phone', () => {
  test.beforeEach(async ({ page, isMobile }) => {
    test.skip(!isMobile, 'phone-only checks');
    await mockApi(page, { signedIn: false });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
  });

  test('the install command stays on one line and scrolls instead of breaking inside the URL', async ({ page }) => {
    // Seen on production: `$ curl -fsSL` / `https://railhook.io/instal` / `l.sh | bash`.
    const box = page.getByTestId('install-command');
    await expect(box).toBeVisible();
    const m = await box.evaluate((el) => ({
      height: el.getBoundingClientRect().height,
      lineHeight: parseFloat(getComputedStyle(el).lineHeight),
      whiteSpace: getComputedStyle(el).whiteSpace,
      overflowX: getComputedStyle(el).overflowX,
    }));
    expect(m.whiteSpace).toBe('pre');
    expect(m.overflowX).toMatch(/auto|scroll/);
    // One line of text plus the vertical padding, not two or three lines.
    expect(m.height).toBeLessThan(m.lineHeight * 2);
  });

  test('every button and non-inline link is at least 40px tall', async ({ page }) => {
    // Scroll through once so sections that mount or reveal on view are laid out.
    await page.evaluate(async () => {
      for (let y = 0; y < document.body.scrollHeight; y += 600) {
        window.scrollTo(0, y);
        await new Promise((r) => setTimeout(r, 80));
      }
      window.scrollTo(0, 0);
    });
    const small = await page.evaluate(() =>
      Array.from(document.querySelectorAll<HTMLElement>('a[href], button, [role=tab]'))
        .filter((el) => {
          const r = el.getBoundingClientRect();
          const s = getComputedStyle(el);
          return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'inline'
            && !el.closest('[aria-hidden="true"]') && r.height < 40;
        })
        .map((el) => `${Math.round(el.getBoundingClientRect().height)}px ${el.tagName.toLowerCase()} "${(el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 30)}"`),
    );
    expect(small).toEqual([]);
  });
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
