import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';
import LandingPage from '../LandingPage';
import LandingNav from '../landing/LandingNav';
import PricingPage from '../PricingPage';

/**
 * "Try the live demo" — on the hero, on /pricing and in the Developers menu — exactly where the
 * deployment runs the demo, and for a visitor who is not signed in. A self-hosted install that
 * never turned it on must not offer a link that answers 404.
 */
const SIGNED_OUT = { auth: { user: null, token: null, isAuthenticated: false } };

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

afterEach(() => {
  delete window.__RAILHOOK__;
});

function demoLinks() {
  return screen.queryAllByRole('link', { name: new RegExp(en.landing.hero.tryDemo) });
}

describe('the live demo entry points', () => {
  it('sits next to the hero calls to action when the demo is on', () => {
    window.__RAILHOOK__ = { publicDemo: true };
    renderPage(<LandingPage />, { path: '/', initialEntry: '/', ...SIGNED_OUT });

    const hero = screen.getByRole('region', { name: /never lose a/i });
    const link = within(hero).getByRole('link', { name: new RegExp(en.landing.hero.tryDemo) });
    expect(link).toHaveAttribute('href', '/demo');
    // Secondary: the first call to action is still signing up.
    expect(within(hero).getAllByRole('link')[0]).toHaveAttribute('href', '/register');
  });

  it('is on the pricing page, beside the free plan', () => {
    window.__RAILHOOK__ = { publicDemo: true };
    renderPage(<PricingPage />, { path: '/pricing', initialEntry: '/pricing', ...SIGNED_OUT });

    expect(demoLinks().map((a) => a.getAttribute('href'))).toEqual(['/demo']);
  });

  it('is in the Developers menu', async () => {
    window.__RAILHOOK__ = { publicDemo: true };
    renderPage(<LandingNav />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    const nav = screen.getByRole('navigation', { name: en.landing.nav.label });

    await userEvent.click(within(nav).getByRole('button', { name: en.landing.nav.developers }));

    expect(within(nav).getByRole('link', { name: new RegExp(en.landing.nav.devDemo) })).toHaveAttribute('href', '/demo');
    expect(within(nav).getByText(en.landing.nav.devDemoBody)).toBeInTheDocument();
  });

  it('is nowhere when the deployment does not run the demo', async () => {
    renderPage(<LandingPage />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    expect(demoLinks()).toHaveLength(0);
  });

  it('is not offered to someone already signed in', () => {
    window.__RAILHOOK__ = { publicDemo: true };
    renderPage(<PricingPage />, { path: '/pricing', initialEntry: '/pricing' });
    expect(demoLinks()).toHaveLength(0);
  });
});
