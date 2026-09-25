import { expect, test, type Page } from '@playwright/test';
import { EVENT_ID, PLATFORM_ORG_ID, PROJECT_ID, WORKFLOW_ID, mockApi, mockNewOrganization } from './fixtures';

/** iOS Safari zooms into focused fields under 16px; jsdom sees neither that nor overflow. */

const PUBLIC = ['/', '/pricing', '/tester', '/tools/webhook-signature', '/security', '/about', '/contact', '/login', '/register', '/forgot-password', '/reset-password?token=e2e', '/verify-email?token=e2e', '/no-such-page'];
const ADMIN = [
  '/admin/dashboard',
  '/admin/projects',
  `/admin/projects/${PROJECT_ID}/endpoints`,
  `/admin/projects/${PROJECT_ID}/deliveries`,
  `/admin/projects/${PROJECT_ID}/events`,
  `/admin/projects/${PROJECT_ID}/events/${EVENT_ID}`,
  `/admin/projects/${PROJECT_ID}/api-keys`,
  `/admin/projects/${PROJECT_ID}/incoming-sources`,
  `/admin/projects/${PROJECT_ID}/workflows/${WORKFLOW_ID}`,
  '/admin/members',
  '/admin/settings',
  '/admin/billing',
  '/admin/platform',
  '/admin/platform/organizations',
  `/admin/platform/organizations/${PLATFORM_ORG_ID}`,
  '/admin/platform/users',
];

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

  test.describe('record lists on a phone', () => {
    const LISTS = [
      `/admin/projects/${PROJECT_ID}/deliveries`,
      `/admin/projects/${PROJECT_ID}/events`,
      `/admin/projects/${PROJECT_ID}/endpoints`,
    ];
    for (const path of LISTS) {
      test(`${path} reads as cards, not a table swiped sideways`, async ({ page, isMobile }) => {
        test.skip(!isMobile, 'phone-only checks');
        await mockApi(page, { signedIn: true });
        await page.goto(path);
        await page.waitForLoadState('networkidle');
        await page.waitForTimeout(400);

        const layout = await page.evaluate(() => {
          const table = document.querySelector('main table');
          if (!table) return null;
          const head = table.querySelector('thead')!.getBoundingClientRect();
          const firstRow = table.querySelector('tbody tr');
          return {
            vw: document.documentElement.clientWidth,
            tableWidth: table.getBoundingClientRect().width,
            headHeight: head.height,
            labelled: firstRow ? firstRow.querySelectorAll('td[data-label]').length : 0,
            rows: table.querySelectorAll('tbody tr').length,
          };
        });
        expect(layout, `${path}: a list table is rendered`).not.toBeNull();
        expect(layout!.rows, `${path}: the fixture rows are listed`).toBeGreaterThan(3);
        expect(layout!.tableWidth, `${path}: the table is no wider than the phone`).toBeLessThanOrEqual(layout!.vw);
        expect(layout!.headHeight, `${path}: the column header row is not drawn`).toBeLessThanOrEqual(1);
        expect(layout!.labelled, `${path}: each card line names its column`).toBeGreaterThan(1);
      });

      test(`${path} has 40px tap targets`, async ({ page, isMobile }) => {
        test.skip(!isMobile, 'phone-only checks');
        await mockApi(page, { signedIn: true });
        await page.goto(path);
        await page.waitForLoadState('networkidle');
        await page.waitForTimeout(400);

        const small = await page.evaluate(() =>
          Array.from(document.querySelectorAll<HTMLElement>('main a[href], main button, main [role=tab], main select, nav a[href]'))
            .filter((el) => {
              const r = el.getBoundingClientRect();
              const s = getComputedStyle(el);
              return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'inline'
                && !el.closest('[aria-hidden="true"], thead') && r.height < 40;
            })
            .map((el) => `${Math.round(el.getBoundingClientRect().height)}px ${el.tagName.toLowerCase()} "${(el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 30)}"`),
        );
        expect(small).toEqual([]);

        const checkboxCells = await page.evaluate(() =>
          Array.from(document.querySelectorAll<HTMLElement>('main td[data-cell="select"]'))
            .map((td) => Math.min(td.getBoundingClientRect().width, td.getBoundingClientRect().height))
            .filter((size) => size < 40),
        );
        expect(checkboxCells, 'selection cells are a 40px target').toEqual([]);
      });
    }
  });

  /** A touch screen fires no dragstart, so the palette can't be drag-only. */
  test.describe('the workflow builder on a phone', () => {
    const BUILDER = `/admin/projects/${PROJECT_ID}/workflows/${WORKFLOW_ID}`;

    test.beforeEach(async ({ page, isMobile }) => {
      test.skip(!isMobile, 'phone-only checks');
      await mockApi(page, { signedIn: true });
      await page.goto(BUILDER);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(600);
    });

    test('keeps Save on screen and leaves the canvas most of it', async ({ page }) => {
      const { width: vw, height: vh } = page.viewportSize()!;

      const save = page.getByRole('button', { name: /^save$/i });
      await expect(save).toBeVisible();
      const saveBox = (await save.boundingBox())!;
      expect(saveBox.x + saveBox.width, 'Save sits inside the screen').toBeLessThanOrEqual(vw + 1);

      const canvasBox = (await page.locator('.react-flow').boundingBox())!;
      expect(Math.round(canvasBox.width), 'the canvas has the full width').toBe(vw);
      expect(canvasBox.height, 'the canvas has most of the height').toBeGreaterThan(vh * 0.5);
    });

    test('adds a node when a palette entry is tapped', async ({ page }) => {
      await expect(page.getByText(/6 nodes/)).toBeVisible();

      await page.getByRole('button', { name: /delay/i }).tap();

      await expect(page.getByText(/7 nodes/)).toBeVisible();
    });

    test('opens the workflow with every node inside the canvas', async ({ page }) => {
      const outside = await page.evaluate(() => {
        const pane = document.querySelector('.react-flow')!.getBoundingClientRect();
        return Array.from(document.querySelectorAll<HTMLElement>('.react-flow__node'))
          .map((node) => node.getBoundingClientRect())
          .filter((box) => box.left < pane.left - 1 || box.right > pane.right + 1
            || box.top < pane.top - 1 || box.bottom > pane.bottom + 1)
          .length;
      });
      expect(outside, 'every node of the loaded workflow is in view').toBe(0);
    });

    test('opens with nothing to save', async ({ page }) => {
      // The canvas reports its own measurements as changes.
      await expect(page.getByRole('button', { name: /^save$/i })).toBeDisabled();
      await expect(page.getByText(/unsaved/i)).toHaveCount(0);
    });
  });

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

test.describe('a brand-new organization', () => {
  test('can open a section from the rail, create a project there, and land in that section', async ({ page, isMobile }) => {
    test.skip(isMobile, 'the rail is a drawer on a phone; the flow is the same');
    await mockNewOrganization(page);
    await page.goto('/admin/projects');

    await page.getByRole('navigation', { name: /navigation/i }).getByRole('link', { name: 'Events' }).click();
    await expect(page).toHaveURL(/\/admin\/start\/events$/);
    await expect(page.getByRole('main').getByRole('heading', { name: 'Events' })).toBeVisible();

    await page.getByRole('button', { name: 'Create project' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Project Name').fill('Checkout');
    await dialog.getByRole('button', { name: 'Create Project' }).click();

    await expect(page).toHaveURL(new RegExp(`/admin/projects/${PROJECT_ID}/events$`));
  });

  test('keeps page header actions inside the window on wide screens', async ({ page, isMobile }) => {
    test.skip(isMobile, 'desktop widths only');
    await mockApi(page, { signedIn: true });
    for (const width of [1280, 1440, 1920, 2560]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/admin/projects');
      const action = page.getByRole('button', { name: 'New Project' });
      await expect(action).toBeVisible();
      const box = await action.boundingBox();
      expect(box, `New Project at ${width}px`).not.toBeNull();
      expect(box!.x + box!.width, `New Project right edge at ${width}px`).toBeLessThanOrEqual(width);
    }
  });
});
