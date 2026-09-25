import userEvent from '@testing-library/user-event';
import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest';
import { act, screen, within } from '@testing-library/react';
import LandingPage from '../LandingPage';
import LandingNav from '../landing/LandingNav';
import { Footer } from '../../layout/PublicLayout';
import { renderPage } from '../../test/renderPage';
import i18n from '../../i18n';
import en from '../../i18n/locales/en.json';
import { FREE_PLAN } from '../landing/plans';

const SIGNED_OUT = { auth: { user: null, token: null, isAuthenticated: false } };

function renderLanding(auth: object = SIGNED_OUT) {
  return renderPage(<LandingPage />, { path: '/', initialEntry: '/', ...auth });
}

function proseText(): string {
  const copy = document.body.cloneNode(true) as HTMLElement;
  copy.querySelectorAll('pre, code').forEach((node) => node.remove());
  return copy.textContent ?? '';
}

function sectionTitled(name: string | RegExp): HTMLElement {
  const section = screen.getByRole('heading', { name }).closest('section') as HTMLElement;
  expect(section, `no section titled ${name}`).not.toBeNull();
  return section;
}

beforeAll(() => {
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
  it('has exactly one h1, and it names what the product is', () => {
    renderLanding();
    const h1s = screen.getAllByRole('heading', { level: 1 });
    expect(h1s).toHaveLength(1);
    expect(h1s[0].textContent).toBe('Webhook infrastructureyou can run yourself');
  });

  it('keeps the words people search for in its headings and lede', () => {
    renderLanding();
    const lede = screen.getByText(en.landing.hero.lead);
    expect(lede.textContent).toMatch(/send webhooks/i);
    expect(lede.textContent).toMatch(/receive them/i);
    expect(lede.textContent).toMatch(/open-source webhook gateway/i);
    expect(screen.getByRole('heading', { name: en.landing.product.out.title })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: en.landing.product.in.title })).toBeInTheDocument();
  });

  it('shows the sections in order: product, reliability, self-hosting, the offer', () => {
    renderLanding();
    const titles = screen.getAllByRole('heading', { level: 2 }).map((h) => h.id);
    expect(titles).toEqual(['product-title', 'reliability-title', 'self-host-title', 'final-title']);
  });

  it('offers signing up first and the install second', () => {
    renderLanding();
    const hero = sectionTitled(/run yourself/);
    const links = within(hero).getAllByRole('link');
    expect(links[0]).toHaveAttribute('href', '/register');
    expect(links[0]).toHaveTextContent(en.landing.hero.startFree);
    expect(links[1]).toHaveAttribute('href', '#self-host');
    expect(document.getElementById('self-host')).not.toBeNull();
  });

  it('sends a signed-in reader to the dashboard instead of the signup', () => {
    renderLanding({});
    const dashboard = screen.getAllByRole('link', { name: en.landing.nav.goToDashboard });
    expect(dashboard.length).toBeGreaterThanOrEqual(2);
    dashboard.forEach((link) => expect(link).toHaveAttribute('href', '/admin/dashboard'));
    expect(screen.queryByRole('link', { name: en.landing.hero.startFree })).toBeNull();
  });

  it('prints the default outgoing retry schedule the backend runs', () => {
    renderLanding();
    const table = within(sectionTitled(/server was down/)).getByRole('table');
    const rows = within(table).getAllByRole('row');
    const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent);
    expect(cells(rows[1]).slice(0, 7)).toEqual(['—', '1m', '5m', '15m', '1h', '6h', '24h']);
    expect(cells(rows[2]).slice(0, 7)).toEqual(['0', '1m', '6m', '21m', '1h 21m', '7h 21m', '31h 21m']);
    expect(table.textContent).toContain(en.landing.reliability.failed);
  });

  it('shows the real install command, once', () => {
    renderLanding();
    const install = screen.getByTestId('install-command');
    expect(install.textContent).toContain('curl -fsSL https://railhook.io/install.sh | bash -s -- \\\n  --domain hooks.example.com --email ops@example.com');
    expect(document.body.textContent?.split('https://railhook.io/install.sh').length).toBe(2);
  });

  it('quotes the free plan from the seeded allowance', () => {
    renderLanding();
    const offer = document.getElementById('cloud') as HTMLElement;
    const text = offer.textContent ?? '';
    expect(text).toContain(new Intl.NumberFormat('en').format(FREE_PLAN.events));
    expect(text).toContain(`${FREE_PLAN.projects} projects`);
    expect(text).toContain(`${FREE_PLAN.retention} days`);
    expect(within(offer).getByRole('link', { name: en.landing.hero.startFree })).toHaveAttribute('href', '/register');
  });

  it('prints no price, and never "coming soon"', () => {
    renderLanding();
    const text = proseText();
    expect(text).not.toContain('$');
    expect(text).not.toMatch(/coming soon|free forever/i);
    expect(text).not.toMatch(/per month|\/mo\b/i);
  });

  it('stays under 600 words in both languages', async () => {
    for (const lng of ['en', 'uk']) {
      await i18n.changeLanguage(lng);
      const { unmount } = renderLanding();
      const words = proseText().split(/\s+/).filter(Boolean);
      expect(words.length, `${lng}: ${words.length} words`).toBeLessThanOrEqual(600);
      unmount();
    }
    await i18n.changeLanguage('en');
  });
});

describe('the attempt log', () => {
  const realIO = window.IntersectionObserver;
  const realMatchMedia = window.matchMedia;

  afterEach(() => {
    window.IntersectionObserver = realIO;
    window.matchMedia = realMatchMedia;
  });

  function reducedMotion(reduce: boolean) {
    window.matchMedia = ((query: string) => ({
      matches: reduce && query.includes('reduce'),
      media: query,
      onchange: null,
      addEventListener() {},
      removeEventListener() {},
      addListener() {},
      removeListener() {},
      dispatchEvent: () => false,
    })) as typeof window.matchMedia;
  }

  const log = () => screen.getByRole('table', { name: en.landing.stage.aria });
  const lit = () => log().querySelectorAll('[data-on="true"]').length;

  it('is finished at once for a reader who asked for less motion', () => {
    reducedMotion(true);
    renderLanding();
    expect(lit()).toBe(5);
    expect(log().textContent).toContain('delivered on attempt 5');
  });

  it('waits off screen, then plays attempt by attempt', () => {
    reducedMotion(false);
    let fire: (() => void) | undefined;
    window.IntersectionObserver = class {
      private readonly callback: IntersectionObserverCallback;
      constructor(callback: IntersectionObserverCallback) {
        this.callback = callback;
      }
      observe(target: Element) {
        fire = () => this.callback([{ isIntersecting: true, target } as IntersectionObserverEntry], this as unknown as IntersectionObserver);
      }
      unobserve() {}
      disconnect() {}
      takeRecords() {
        return [];
      }
    } as unknown as typeof IntersectionObserver;

    vi.useFakeTimers();
    try {
      renderLanding();
      expect(lit()).toBe(0);
      expect(log().textContent).toContain(en.landing.stage.sending);
      act(() => fire?.());
      act(() => vi.advanceTimersByTime(600));
      expect(lit()).toBe(1);
      expect(log().textContent).toContain(en.landing.stage.retrying);
      act(() => vi.advanceTimersByTime(5000));
      expect(lit()).toBe(5);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('LandingNav', () => {
  it('links the product, self-hosting, pricing and docs, and offers the signup', () => {
    renderPage(<LandingNav />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    const nav = screen.getByRole('navigation', { name: en.landing.nav.label });
    const hrefs = within(nav).getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(expect.arrayContaining(['/#product', '/#self-host', '/pricing', '/docs/', '/register', '/login']));
    expect(hrefs).not.toContain('/changelog');
  });

  it('carries the language switch, and offers it first inside the menu on a phone', async () => {
    renderPage(<LandingNav />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    const nav = screen.getByRole('navigation', { name: en.landing.nav.label });
    const switcher = within(nav).getByRole('group', { name: en.settings.language });
    expect(within(switcher).getByRole('button', { name: 'EN' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(switcher).getByRole('button', { name: 'UK' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: en.landing.nav.openMenu }));
    const panel = document.getElementById('landing-mobile-nav') as HTMLElement;
    const first = within(panel).getAllByRole('listitem')[0];
    expect(within(first).getByRole('group', { name: en.settings.language })).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: en.landing.nav.pricing })).toHaveAttribute('href', '/pricing');
  });

  it('sends the docs link to the Ukrainian docs when the reader reads Ukrainian', async () => {
    await i18n.changeLanguage('uk');
    try {
      renderPage(<LandingNav />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
      const nav = screen.getByRole('navigation', { name: /./ });
      expect(within(nav).getAllByRole('link').map((a) => a.getAttribute('href'))).toContain('/docs/uk/');
    } finally {
      await i18n.changeLanguage('en');
    }
  });
});

describe('Footer', () => {
  beforeEach(() => {
    window.__RAILHOOK__ = { publicBlog: true };
  });
  afterEach(() => {
    delete window.__RAILHOOK__;
  });

  it('keeps the theme toggle and the licence, and no language switch', () => {
    renderPage(<Footer />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    expect(screen.getByRole('button', { name: en.nav.toggleTheme })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: en.settings.language })).toBeNull();
    expect(screen.getByText(`© ${new Date().getFullYear()} Railhook · MIT`)).toBeInTheDocument();

    const hrefs = screen.getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(expect.arrayContaining([
      'https://github.com/vadymkykalo/railhook/issues',
      'https://github.com/vadymkykalo/railhook/releases',
      '/pricing',
      '/docs/tools/mcp/',
      '/tools/webhook-signature',
      '/blog',
    ]));
    expect(hrefs).not.toContain('/changelog');
    for (const gone of ['/about', '/security', '/contact']) expect(hrefs).not.toContain(gone);
  });

  it('lists the company pages under their own heading', () => {
    renderPage(<Footer />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    const company = screen.getByRole('heading', { name: en.footer.company }).parentElement as HTMLElement;
    expect(within(company).getAllByRole('link').map((a) => a.getAttribute('href')))
      .toEqual(['/blog', '/privacy', '/terms']);
  });

  describe('connect with us', () => {
    function connectRow() {
      renderPage(<Footer />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
      return screen.getByRole('list', { name: en.footer.connect });
    }

    it('always links to the repository, in a new tab', () => {
      const github = within(connectRow()).getByRole('link', { name: en.footer.connectGithub });
      expect(github).toHaveAttribute('href', 'https://github.com/vadymkykalo/railhook');
      expect(github).toHaveAttribute('target', '_blank');
      expect(github.getAttribute('rel')).toContain('noopener');
    });

    it('offers support mail on the configured domain', () => {
      window.__RAILHOOK__ = { contactDomain: 'example.org' };
      const email = within(connectRow()).getByRole('link', { name: en.footer.connectEmail });
      expect(email).toHaveAttribute('href', 'mailto:support@example.org');
    });

    it('offers no mail without a contact domain, as the contact page does not', () => {
      delete window.__RAILHOOK__;
      const row = connectRow();
      expect(within(row).queryByRole('link', { name: en.footer.connectEmail })).toBeNull();
      expect(row.querySelector('a[href^="mailto:"]')).toBeNull();
    });
  });
});
