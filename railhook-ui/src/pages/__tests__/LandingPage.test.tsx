import userEvent from '@testing-library/user-event';
import { describe, it, expect, beforeAll, afterEach } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
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

/**
 * The page's prose, without its code samples. A PHP sample is full of `$` and a code block is
 * not words a reader has to get through, so the price and word-count checks read everything
 * except what sits inside `pre` and `code`.
 */
function proseText(): string {
  const copy = document.body.cloneNode(true) as HTMLElement;
  copy.querySelectorAll('pre, code').forEach((node) => node.remove());
  return copy.textContent ?? '';
}

/** The developer band, asserted to be the page's last section. */
function developerSection(container: HTMLElement): HTMLElement {
  const section = screen.getByRole('heading', { name: en.landing.developer.title }).closest('section') as HTMLElement;
  expect(section).not.toBeNull();
  expect(container.lastElementChild).toBe(section);
  return section;
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

/** The section a heading titles. */
function sectionTitled(name: string): HTMLElement {
  const section = screen.getByRole('heading', { name }).closest('section') as HTMLElement;
  expect(section, `no section titled ${name}`).not.toBeNull();
  return section;
}

describe('LandingPage', () => {
  it('has a heading for each of its seven sections', () => {
    renderLanding();
    const headings = [
      'Never lose a webhook',
      en.landing.directions.title,
      en.landing.reliability.title,
      en.landing.architecture.title,
      en.landing.product.title,
      en.landing.run.title,
      en.landing.developer.title,
    ];
    for (const name of headings) {
      expect(screen.getByRole('heading', { name }), `missing section: ${name}`).toBeInTheDocument();
    }
  });

  it('shows the product straight after the hero, and what keeps events safe right after the reliability section', () => {
    renderLanding();
    const hero = screen.getByRole('heading', { level: 1 }).closest('section') as HTMLElement;
    const product = sectionTitled(en.landing.product.title);
    const directions = sectionTitled(en.landing.directions.title);
    const reliability = sectionTitled(en.landing.reliability.title);
    const architecture = sectionTitled(en.landing.architecture.title);
    expect(hero.nextElementSibling).toBe(product);
    expect(product.nextElementSibling).toBe(directions);
    expect(reliability.nextElementSibling).toBe(architecture);
  });

  it('draws the architecture as one picture with a text alternative naming what it runs on', () => {
    renderLanding();
    const architecture = sectionTitled(en.landing.architecture.title);
    const figure = within(architecture).getByRole('figure');
    const picture = within(figure).getByRole('img');
    const name = picture.getAttribute('aria-label') ?? '';
    expect(name).toBe(en.landing.architecture.diagramAria);
    for (const part of ['PostgreSQL', 'Kafka', 'Redis']) {
      expect(name, `the text alternative should name ${part}`).toContain(part);
    }
    expect(within(figure).getByRole('img', { name: /Stripe/ })).toBe(picture);
  });

  it('draws what it runs on with the vendors’ own logos, bundled with the page and silent to a screen reader', () => {
    renderLanding();
    const figure = within(sectionTitled(en.landing.architecture.title)).getByRole('figure');
    const logos = Array.from(figure.querySelectorAll('img'));
    const sources = logos.map((logo) => logo.getAttribute('src'));
    for (const name of ['postgresql', 'redis', 'apachekafka']) {
      expect(sources, `the diagram should draw the ${name} logo`).toContain(`/logos/brand/${name}.svg`);
    }
    for (const logo of logos) {
      expect(logo, 'a logo inside the described figure is decorative').toHaveAttribute('alt', '');
    }
  });

  it('draws the hero map with the vendors’ own logos, each one named in the map’s text alternative', () => {
    renderLanding();
    const map = screen.getByRole('img', { name: en.landing.map.aria });
    const logos = Array.from(map.querySelectorAll('image')).map((image) => image.getAttribute('href'));
    for (const [file, name] of [['stripe', 'Stripe'], ['github', 'GitHub'], ['shopify', 'Shopify'], ['slack', 'Slack']]) {
      expect(logos, `the map should draw the ${name} logo`).toContain(`/logos/brand/${file}.svg`);
      expect(en.landing.map.aria, `the map's text alternative should name ${name}`).toContain(name);
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
    const text = proseText();
    expect(text).not.toContain('$');
    expect(text).not.toMatch(/coming soon|free forever/i);
    expect(text).not.toMatch(/per month|\/mo\b/i);
  });

  it('stays under 600 words in both languages', async () => {
    for (const lng of ['en', 'uk']) {
      await i18n.changeLanguage(lng);
      const { unmount } = renderLanding();
      // Code samples are not counted: see proseText().
      const words = proseText().split(/\s+/).filter(Boolean);
      expect(words.length, `${lng}: ${words.length} words`).toBeLessThanOrEqual(600);
      unmount();
    }
    await i18n.changeLanguage('en');
  });
});

describe('DeveloperSection', () => {
  it('ends the page with the docs, the API reference, Standard Webhooks and the MCP server', () => {
    const { container } = renderLanding();
    const section = developerSection(container);
    const hrefs = within(section).getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(expect.arrayContaining([
      '/docs/',
      '/docs/api-reference/',
      'https://www.standardwebhooks.com/',
      '/docs/tools/mcp/',
    ]));
    const external = within(section).getAllByRole('link')
      .find((a) => a.getAttribute('href') === 'https://www.standardwebhooks.com/') as HTMLElement;
    expect(external).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('switches the code sample between Node.js, Python, PHP and cURL', () => {
    const { container } = renderLanding();
    const section = developerSection(container);
    const tablist = within(section).getByRole('tablist');
    const tabs = within(tablist).getAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['Node.js', 'Python', 'PHP', 'cURL']);

    const panel = () => within(section).getByRole('tabpanel');
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    expect(panel().textContent).toContain("from '@railhook/node'");

    fireEvent.click(tabs[1]);
    expect(tabs[1]).toHaveAttribute('aria-selected', 'true');
    expect(panel().textContent).toContain('from railhook import Railhook');
    expect(panel().textContent).not.toContain("from '@railhook/node'");

    fireEvent.click(tabs[2]);
    expect(panel().textContent).toContain('$client->events->send(');

    fireEvent.click(tabs[3]);
    expect(panel().textContent).toContain('curl -X POST');
  });

  it('moves between languages with the arrow keys', () => {
    const { container } = renderLanding();
    const tabs = within(within(developerSection(container)).getByRole('tablist')).getAllByRole('tab');

    fireEvent.keyDown(tabs[0], { key: 'ArrowLeft' });
    expect(tabs[3]).toHaveAttribute('aria-selected', 'true');
    expect(tabs[3]).toHaveFocus();

    fireEvent.keyDown(tabs[3], { key: 'ArrowRight' });
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    expect(tabs[0]).toHaveFocus();
  });

  it('keeps sign-up in the last section, and prints the install command only in the hero', () => {
    const { container } = renderLanding();
    const section = developerSection(container);
    expect(within(section).getByRole('link', { name: en.landing.hero.startFree })).toHaveAttribute('href', '/register');
    expect(within(section).getByRole('link', { name: en.landing.developer.readDocs })).toHaveAttribute('href', '/docs/');
    expect(section.textContent).not.toContain(INSTALL);
    expect(document.body.textContent?.split(INSTALL).length).toBe(2);
  });

  it('sends a signed-in reader to the dashboard from the last section', () => {
    const { container } = renderLanding({});
    const section = developerSection(container);
    expect(within(section).getByRole('link', { name: en.landing.nav.goToDashboard })).toHaveAttribute('href', '/admin/dashboard');
    expect(within(section).queryByRole('link', { name: en.landing.hero.startFree })).toBeNull();
  });
});

describe('LandingNav', () => {
  it('keeps the header to nine things to press', () => {
    renderPage(<LandingNav />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    const nav = screen.getByRole('navigation', { name: en.landing.nav.label });
    const interactive = [...within(nav).queryAllByRole('link'), ...within(nav).queryAllByRole('button')];
    expect(interactive.length).toBeLessThanOrEqual(9);

    const hrefs = within(nav).getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(expect.arrayContaining(['/#product', '/pricing', '/#run', '/about', '/register', '/login']));
    // Pricing covers both the cloud plan and self-hosting, so the header has no separate "Cloud".
    expect(within(nav).queryByRole('link', { name: 'Cloud' })).toBeNull();
  });

  it('keeps docs, the CLI and the MCP server behind a Developers menu, each with a line on what it is', async () => {
    renderPage(<LandingNav />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    const nav = screen.getByRole('navigation', { name: en.landing.nav.label });
    const toggle = within(nav).getByRole('button', { name: en.landing.nav.developers });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(within(nav).getByRole('link', { name: new RegExp(en.landing.nav.devCli) })).toHaveAttribute('href', '/docs/tools/cli/');
    expect(within(nav).getByRole('link', { name: new RegExp(en.landing.nav.devMcp) })).toHaveAttribute('href', '/docs/tools/mcp/');
    expect(within(nav).getByRole('link', { name: new RegExp(en.landing.nav.devDocs) })).toHaveAttribute('href', '/docs/');
    expect(within(nav).getByText(en.landing.nav.devMcpBody)).toBeInTheDocument();

    await userEvent.keyboard('{Escape}');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).toHaveFocus();
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
      '/contact',
      '/pricing',
      '/docs/tools/mcp/',
      '/tools/webhook-signature',
      '/about',
      '/security',
      '/changelog',
    ]));
  });

  it('lists the company pages under their own heading', () => {
    renderPage(<Footer />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    const company = screen.getByRole('heading', { name: en.footer.company }).parentElement as HTMLElement;
    expect(within(company).getAllByRole('link').map((a) => a.getAttribute('href')))
      .toEqual(['/about', '/security', '/changelog', '/contact', '/privacy', '/terms']);
  });

  describe('connect with us', () => {
    afterEach(() => {
      delete window.__RAILHOOK__;
    });

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
      const row = connectRow();
      expect(within(row).queryByRole('link', { name: en.footer.connectEmail })).toBeNull();
      expect(row.querySelector('a[href^="mailto:"]')).toBeNull();
    });
  });
});

/**
 * The directions cards and the hero show delivery happening rather than naming it. What these
 * hold in place: the finished picture is what a crawler, a test and a reader who asked for less
 * motion get; the loop only runs on screen; and none of it is read out.
 */
describe('LandingPage motion', () => {
  const realIO = window.IntersectionObserver;
  const realMatchMedia = window.matchMedia;
  const realGetContext = HTMLCanvasElement.prototype.getContext;

  afterEach(() => {
    window.IntersectionObserver = realIO;
    window.matchMedia = realMatchMedia;
    HTMLCanvasElement.prototype.getContext = realGetContext;
  });

  /** An observer that reports every observed element on screen at once. */
  function everythingOnScreen() {
    window.IntersectionObserver = class {
      private readonly callback: IntersectionObserverCallback;
      constructor(callback: IntersectionObserverCallback) {
        this.callback = callback;
      }
      observe(target: Element) {
        this.callback([{ isIntersecting: true, target } as IntersectionObserverEntry], this as unknown as IntersectionObserver);
      }
      unobserve() {}
      disconnect() {}
      takeRecords() {
        return [];
      }
    } as unknown as typeof IntersectionObserver;
    // jsdom has no canvas; the hero's backdrop must cope with a context it cannot get.
    HTMLCanvasElement.prototype.getContext = (() => null) as typeof realGetContext;
  }

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

  it('shows both directions happening: one event to three customers, requests from real services checked on the way in', () => {
    renderLanding();
    const section = sectionTitled(en.landing.directions.title);
    for (const host of ['acme.com', 'shop.io', 'crm.dev']) {
      expect(section.textContent, `the send scene should deliver to ${host}`).toContain(host);
    }
    const logos = Array.from(section.querySelectorAll('image')).map((image) => image.getAttribute('href'));
    for (const name of ['stripe', 'github', 'shopify']) {
      expect(logos, `the receive scene should draw the ${name} logo`).toContain(`/logos/brand/${name}.svg`);
    }
  });

  it('rests on the finished picture until it is on screen: every delivery arrived, genuine requests verified, a forged one refused', () => {
    renderLanding();
    const section = sectionTitled(en.landing.directions.title);
    const scenes = section.querySelectorAll('[data-motion]');
    expect(scenes).toHaveLength(2);
    scenes.forEach((scene) => expect(scene).toHaveAttribute('data-motion', 'static'));
    expect(section.textContent).toContain(en.landing.directions.scene.delivered);
    expect(section.textContent).toContain(en.landing.directions.scene.verified);
    expect(section.textContent).toContain(en.landing.directions.scene.rejected);
  });

  it('keeps each scene out of the accessibility tree and says in one sentence what it shows', () => {
    renderLanding();
    const section = sectionTitled(en.landing.directions.title);
    section.querySelectorAll('[data-motion]').forEach((scene) => {
      expect(scene.querySelector('svg')?.closest('[aria-hidden="true"]'), 'the drawing is decorative').not.toBeNull();
    });
    expect(within(section).getByText(en.landing.directions.scene.sendSummary)).toBeInTheDocument();
    expect(within(section).getByText(en.landing.directions.scene.receiveSummary)).toBeInTheDocument();
  });

  it('plays once on screen, and never for a reader who asked for less motion', () => {
    everythingOnScreen();
    reducedMotion(false);
    const first = renderLanding();
    const running = document.querySelectorAll('[data-motion]');
    expect(running.length).toBeGreaterThanOrEqual(3);
    running.forEach((scene) => expect(scene, scene.tagName).toHaveAttribute('data-motion', 'running'));
    first.unmount();

    reducedMotion(true);
    renderLanding();
    document.querySelectorAll('[data-motion]').forEach((scene) => expect(scene, scene.tagName).toHaveAttribute('data-motion', 'static'));
  });

  it('delivers webhooks quietly behind the hero, silent to a screen reader', () => {
    renderLanding();
    const hero = screen.getByRole('heading', { name: 'Never lose a webhook' }).closest('section') as HTMLElement;
    const backdrop = hero.querySelector('canvas');
    expect(backdrop, 'the hero should have a delivery backdrop').not.toBeNull();
    expect(backdrop).toHaveAttribute('aria-hidden', 'true');
  });
});
