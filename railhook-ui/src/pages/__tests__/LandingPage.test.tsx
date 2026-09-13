import { describe, it, expect, beforeAll } from 'vitest';
import { screen, within } from '@testing-library/react';
import LandingPage from '../LandingPage';
import LandingNav from '../landing/LandingNav';
import { Footer } from '../../layout/PublicLayout';
import { renderPage } from '../../test/renderPage';
import i18n from '../../i18n';
import en from '../../i18n/locales/en.json';
import { FREE_PLAN } from '../landing/plans';

/**
 * The landing page has two readers: someone deciding whether to try Railhook, and someone who
 * has decided and wants the command. So it states two offers side by side — the free cloud plan
 * and the self-hosted install — and it states them without a price, because there are no paid
 * plans to price yet.
 *
 * What these tests hold in place is what regressed before or is easy to drift: a hand-typed
 * allowance that stops matching the seeded plan, a pricing grid creeping back, cloud described
 * as not existing, and a header that grew to eleven items.
 */
const SIGNED_OUT = { auth: { user: null, token: null, isAuthenticated: false } };
const INSTALL = 'curl -fsSL https://railhook.io/install.sh | bash';

function renderLanding(auth: object = SIGNED_OUT) {
  return renderPage(<LandingPage />, { path: '/', initialEntry: '/', ...auth });
}

beforeAll(() => {
  /* jsdom implements neither, and both are called on mount: the hash effect
     scrolls, and Reveal observes. */
  window.scrollTo = () => {};
  if (!('IntersectionObserver' in window)) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).IntersectionObserver = class {
      observe() {}
      disconnect() {}
    };
  }
});

describe('LandingPage', () => {
  it('has a heading for each of its six sections', () => {
    renderLanding();
    const headings = [
      'Never lose a webhook',
      en.landing.directions.title,
      en.landing.reliability.title,
      en.landing.product.title,
      en.landing.run.title,
      en.landing.closing.title,
    ];
    for (const name of headings) {
      expect(screen.getByRole('heading', { name }), `missing section: ${name}`).toBeInTheDocument();
    }
  });

  it('shows the install command, exactly, with a copy button beside it', () => {
    renderLanding();
    const install = document.getElementById('install') as HTMLElement;
    expect(install).not.toBeNull();
    expect(within(install).getByText(INSTALL)).toBeInTheDocument();
    expect(within(install).getByRole('button', { name: en.landing.install.copyAria })).toBeInTheDocument();
  });

  it('offers one install command for every server, not a choice of methods', () => {
    renderLanding();
    const install = document.getElementById('install') as HTMLElement;
    expect(within(install).queryByRole('tablist')).toBeNull();
    expect(install.textContent).not.toMatch(/helm|make up|git clone/);
  });

  it('offers the free cloud plan first and the install second', () => {
    renderLanding();
    expect(screen.getAllByRole('link', { name: en.landing.hero.startFree })[0]).toHaveAttribute('href', '/register');
    expect(screen.getByRole('link', { name: en.landing.hero.install })).toHaveAttribute('href', '#install');
    expect(screen.getByText('Railhook Cloud is free right now — no card needed.')).toBeInTheDocument();
  });

  it('sends a signed-in reader to the dashboard instead of the signup', () => {
    renderLanding({});
    expect(screen.getAllByRole('link', { name: en.landing.nav.goToDashboard })[0])
      .toHaveAttribute('href', '/admin/dashboard');
    expect(screen.queryByRole('link', { name: en.landing.hero.startFree })).toBeNull();
  });

  it('describes the cloud plan with the seeded free allowance and a signup link', () => {
    renderLanding();
    const cloud = document.getElementById('cloud') as HTMLElement;
    expect(cloud).not.toBeNull();
    expect(cloud.closest('#run')).not.toBeNull();

    const text = cloud.textContent ?? '';
    expect(text).toContain('Free right now');
    expect(text).toContain("Later we'll add paid plans with support and higher limits.");
    expect(text).toContain(new Intl.NumberFormat('en').format(FREE_PLAN.events));
    expect(text).toContain(`${FREE_PLAN.projects} projects`);
    expect(text).toContain(`${FREE_PLAN.retention} days`);
    expect(within(cloud).getByRole('link', { name: en.landing.run.cloud.cta })).toHaveAttribute('href', '/register');
  });

  it('prints no price, no plan grid and no "coming soon"', () => {
    renderLanding();
    expect(document.getElementById('plans')).toBeNull();
    const text = document.body.textContent ?? '';
    expect(text).not.toContain('$');
    expect(text).not.toMatch(/coming soon|free forever/i);
    expect(text).not.toMatch(/per month|\/mo\b/i);
  });

  it('stays under 600 words in both languages', async () => {
    for (const lng of ['en', 'uk']) {
      await i18n.changeLanguage(lng);
      const { unmount } = renderLanding();
      const words = (document.body.textContent ?? '').split(/\s+/).filter(Boolean);
      expect(words.length, `${lng}: ${words.length} words`).toBeLessThanOrEqual(600);
      unmount();
    }
    await i18n.changeLanguage('en');
  });
});

describe('LandingNav', () => {
  it('keeps the header to eight things to press', () => {
    renderPage(<LandingNav />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    const nav = screen.getByRole('navigation', { name: en.landing.nav.label });
    const interactive = [...within(nav).queryAllByRole('link'), ...within(nav).queryAllByRole('button')];
    expect(interactive.length).toBeLessThanOrEqual(8);

    const hrefs = within(nav).getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(expect.arrayContaining(['/#product', '/#run', '/docs/', '/register', '/login']));
    expect(hrefs).not.toContain('/pricing');
  });
});

describe('Footer', () => {
  it('carries the language and theme controls the header gave up, and the licence', () => {
    renderPage(<Footer />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    expect(screen.getByRole('group', { name: en.settings.language })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.nav.toggleTheme })).toBeInTheDocument();
    expect(screen.getByText(`© ${new Date().getFullYear()} Railhook · MIT`)).toBeInTheDocument();

    const hrefs = screen.getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(expect.arrayContaining([
      'https://github.com/vadymkykalo/railhook/issues',
      'https://github.com/vadymkykalo/railhook/releases',
      'https://github.com/vadymkykalo/railhook/blob/main/SECURITY.md',
      '/contact',
    ]));
    expect(hrefs).not.toContain('/pricing');
  });
});
