import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import { PrivacyPage, TermsPage } from '../LegalPage';

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(uiRoot, p), 'utf8');

/**
 * Google publishes the "Continue with Google" consent screen only with a privacy policy and a
 * terms of service link on it, and the registration form asks people to agree to both. Neither
 * page existed.
 */
describe('legal pages', () => {
  it('the privacy policy renders, with the statements Google requires of it', async () => {
    renderPage(<PrivacyPage />, { path: '/privacy', initialEntry: '/privacy' });

    expect(await screen.findByRole('heading', { level: 1, name: 'Privacy Policy' })).toBeInTheDocument();
    const text = document.body.textContent ?? '';
    expect(text).toMatch(/Limited Use/);
    expect(text).toMatch(/openid, email and profile/);
    expect(text).toMatch(/support@railhook\.io/);
    expect(text).toMatch(/7 days/);
    expect(screen.getByRole('link', { name: 'Terms of Service' })).toHaveAttribute('href', '/terms');
  });

  it('the terms of service render, with the Free plan limits', async () => {
    renderPage(<TermsPage />, { path: '/terms', initialEntry: '/terms' });

    expect(await screen.findByRole('heading', { level: 1, name: 'Terms of Service' })).toBeInTheDocument();
    const text = document.body.textContent ?? '';
    expect(text).toMatch(/10,000 events per month/);
    expect(text).toMatch(/MIT license/);
    expect(text).toMatch(/laws of Ukraine/);
    expect(screen.getByRole('link', { name: 'Privacy Policy' })).toHaveAttribute('href', '/privacy');
  });

  it('are routed, prerendered and listed in the sitemap', () => {
    const router = read('src/router.tsx');
    expect(router).toMatch(/path: '\/privacy'/);
    expect(router).toMatch(/path: '\/terms'/);
    const routes = read('scripts/public-routes.mjs');
    expect(routes).toMatch(/path: '\/privacy'/);
    expect(routes).toMatch(/path: '\/terms'/);
    const sitemap = read('public/sitemap.xml');
    expect(sitemap).toMatch(/\/privacy</);
    expect(sitemap).toMatch(/\/terms</);
  });

  it('are linked from the footer', () => {
    const layout = read('src/layout/PublicLayout.tsx');
    expect(layout).toMatch(/<RouteLink to="\/privacy">/);
    expect(layout).toMatch(/<RouteLink to="\/terms">/);
  });
});
