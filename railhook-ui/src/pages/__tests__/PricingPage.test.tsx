import { afterEach, describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import '../../i18n';
import i18n from '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';
import PricingPage from '../PricingPage';
import { FREE_PLAN } from '../landing/plans';


function renderPricing() {
  return renderPage(<PricingPage />, {
    path: '/pricing',
    initialEntry: '/pricing',
    auth: { user: null, token: null, isAuthenticated: false },
  });
}

function jsonLd(): { '@type': string; mainEntity: { name: string; acceptedAnswer: { text: string } }[] } {
  const node = document.head.querySelector('script[type="application/ld+json"][data-page="pricing"]');
  expect(node, 'FAQPage JSON-LD').not.toBeNull();
  return JSON.parse(node!.textContent ?? '{}');
}

afterEach(async () => {
  await i18n.changeLanguage('en');
});

describe('PricingPage', () => {
  it('names the page for search: title, description and canonical', () => {
    renderPricing();
    expect(document.title).toBe(en.meta.pricing.title);
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toMatch(/\/pricing$/);
  });

  it('quotes the free cloud plan from the seeded allowance, with a signup link', () => {
    renderPricing();
    const cloud = screen.getByRole('heading', { name: en.pricing.cloud.title }).closest('article') as HTMLElement;
    const text = cloud.textContent ?? '';
    expect(text).toContain(new Intl.NumberFormat('en').format(FREE_PLAN.events));
    expect(text).toContain(String(FREE_PLAN.endpointsPerProject));
    expect(text).toContain(String(FREE_PLAN.retention));
    expect(within(cloud).getByRole('link', { name: en.pricing.cloud.cta })).toHaveAttribute('href', '/register');
  });

  it('promises everything on your own servers, with the install guide', () => {
    renderPricing();
    const selfHost = screen.getByRole('heading', { name: en.pricing.selfHosted.title }).closest('article') as HTMLElement;
    expect(selfHost.textContent).toMatch(/MIT/);
    expect(within(selfHost).getByRole('link', { name: en.pricing.selfHosted.cta }))
      .toHaveAttribute('href', '/docs/self-hosting/overview/');
  });

  it('answers the questions asked before signing up, and publishes the same answers as FAQPage', () => {
    renderPricing();
    const data = jsonLd();
    expect(data['@type']).toBe('FAQPage');
    const questions = Object.values(en.pricing.faq).filter((e): e is { q: string; a: string } => typeof e === 'object');
    expect(data.mainEntity.map((e) => e.name)).toEqual(questions.map((e) => e.q));
    for (const { q } of questions) {
      expect(screen.getByText(q)).toBeInTheDocument();
    }
  });

  it('prints no other vendor\u2019s prices', () => {
    renderPricing();
    expect(document.body.textContent).not.toMatch(/Svix|Hookdeck|Convoy|Hook0|\$\d|€\d/);
  });

  it('removes its structured data when the reader leaves, so another page does not inherit it', () => {
    const { unmount } = renderPricing();
    unmount();
    expect(document.head.querySelector('script[data-page="pricing"]')).toBeNull();
  });

  it('switches language, structured data included', async () => {
    await i18n.changeLanguage('uk');
    renderPricing();
    const uk = (await import('../../i18n/locales/uk.json')).default;
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(uk.pricing.title);
    expect(jsonLd().mainEntity[0].name).toBe(uk.pricing.faq.limit.q);
  });
});
