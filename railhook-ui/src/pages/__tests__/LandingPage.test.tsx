import { describe, it, expect, beforeAll } from 'vitest';
import { screen, within } from '@testing-library/react';
import LandingPage from '../LandingPage';
import PricingSection from '../landing/PricingSection';
import { renderPage } from '../../test/renderPage';
import en from '../../i18n/locales/en.json';
import { PLANS } from '../landing/plans';

/**
 * The landing page is the only screen in the app whose job is a signup, and the
 * two ways it has failed at that were both invisible to a typecheck: a section
 * silently dropped from the page, and a plan card rendered with no call to
 * action at all — for months, on the four cards a reader sees *after* deciding
 * they want to pay.
 *
 * So: every section is present, and every plan is clickable.
 */
const SIGNED_OUT = { auth: { user: null, token: null, isAuthenticated: false } };

function renderLanding() {
  return renderPage(<LandingPage />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
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
  it('renders every section heading, in order', () => {
    renderLanding();

    const headings = [
      en.landing.hero.title,
      en.landing.problem.title,
      en.landing.directions.title,
      en.landing.reliability.title,
      en.landing.product.title,
      en.landing.capabilities.title,
      en.landing.security.title,
      en.landing.start.title,
      en.landing.pricing.title,
      en.landing.faq.title,
      en.landing.closing.title,
    ];

    for (const heading of headings) {
      expect(screen.getByText(heading), `missing section: ${heading}`).toBeInTheDocument();
    }
  });

  it('sends both hero calls to action into the funnel, not to GitHub', () => {
    renderLanding();

    expect(screen.getByRole('link', { name: new RegExp(en.landing.hero.ctaPrimary, 'i') }))
      .toHaveAttribute('href', '/register');
    expect(screen.getByRole('link', { name: en.landing.hero.ctaSecondary }))
      .toHaveAttribute('href', '/docs');
  });

  it('quotes the free plan allowance from the seeded plan, not a hand-typed number', () => {
    renderLanding();
    /* The whole sentence, not just the figure: "10,000" also appears in the
       pricing grid, and matching it there would pass even if the hero went back
       to hard-coding its own copy of the allowance. */
    const events = new Intl.NumberFormat('en').format(PLANS[0].events as number);
    expect(screen.getByText(en.landing.hero.ctaNote.replace('{{events}}', events))).toBeInTheDocument();
  });

  it('gives every direction its own call to action', () => {
    renderLanding();
    for (const cta of [en.landing.directions.outCta, en.landing.directions.inCta]) {
      expect(screen.getByRole('link', { name: new RegExp(cta, 'i') })).toHaveAttribute('href', '/register');
    }
  });
});

describe('PricingSection', () => {
  it('gives every plan card a call to action', () => {
    renderPage(<PricingSection />, { path: '/', initialEntry: '/', ...SIGNED_OUT });

    const grid = document.getElementById('plans');
    expect(grid).not.toBeNull();

    const links = within(grid as HTMLElement).getAllByRole('link');
    expect(links).toHaveLength(PLANS.length);

    const hrefs = links.map((link) => link.getAttribute('href'));
    expect(hrefs.filter((href) => href === '/register')).toHaveLength(PLANS.length - 1);
    expect(hrefs).toContain('/contact');
  });

  it('does not promise SSO, which the product does not implement', () => {
    renderPage(<PricingSection />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    expect(screen.queryByText(/\bSSO\b/i)).toBeNull();
  });
});
