import { afterEach, describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import '../../i18n';
import i18n from '../../i18n';
import en from '../../i18n/locales/en.json';
import uk from '../../i18n/locales/uk.json';
import { renderPage } from '../../test/renderPage';
import SecurityPage from '../SecurityPage';
import { REPO_URL } from '../landing/plans';

/**
 * How Railhook protects data, stated only as far as the code and the privacy policy bear it out.
 * A security page is read by the people most likely to check it, so a claim it cannot back —
 * a certification, an uptime figure — costs more trust than the page earns.
 */
function renderSecurity() {
  return renderPage(<SecurityPage />, {
    path: '/security',
    initialEntry: '/security',
    auth: { user: null, token: null, isAuthenticated: false },
  });
}

afterEach(async () => {
  delete window.__RAILHOOK__;
  await i18n.changeLanguage('en');
});

describe('SecurityPage', () => {
  it('names the page for search: title, description and canonical', () => {
    renderSecurity();
    expect(document.title).toBe(en.meta.security.title);
    expect(document.head.querySelector('meta[name="description"]')?.getAttribute('content')).toBe(en.meta.security.description);
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toMatch(/\/security$/);
  });

  it('says where Railhook Cloud runs and who processes data for it, as the privacy policy does', () => {
    renderSecurity();
    const text = document.body.textContent ?? '';
    for (const processor of ['Hetzner', 'Helsinki', 'Cloudflare', 'Resend']) {
      expect(text).toContain(processor);
    }
  });

  it('says how credentials are stored', () => {
    renderSecurity();
    const text = document.body.textContent ?? '';
    expect(text).toContain('AES-256-GCM');
    expect(text).toContain('SHA-256');
    expect(text).toMatch(/bcrypt/i);
  });

  it('claims no certification or uptime the project does not have', () => {
    renderSecurity();
    expect(document.body.textContent).not.toMatch(/SOC ?2|ISO ?27001|HIPAA|PCI|GDPR[- ]certified|99\.\d+ ?%/i);
  });

  it('sends a vulnerability report to GitHub’s private advisories and the security policy', () => {
    renderSecurity();
    const report = screen.getByRole('heading', { name: en.security.report.title }).closest('section') as HTMLElement;
    expect(within(report).getByRole('link', { name: en.security.report.advisory }))
      .toHaveAttribute('href', `${REPO_URL}/security/advisories/new`);
    expect(within(report).getByRole('link', { name: en.security.report.policy }))
      .toHaveAttribute('href', `${REPO_URL}/blob/main/SECURITY.md`);
  });

  it('offers no mail address on a deployment without a contact domain', () => {
    renderSecurity();
    expect(document.querySelector('a[href^="mailto:"]')).toBeNull();
  });

  it('offers the support address where the deployment has one', () => {
    window.__RAILHOOK__ = { contactDomain: 'example.org' };
    renderSecurity();
    expect(screen.getByRole('link', { name: 'support@example.org' })).toHaveAttribute('href', 'mailto:support@example.org');
  });

  it('switches language', async () => {
    await i18n.changeLanguage('uk');
    renderSecurity();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(uk.security.title);
  });

  it('has no detectable accessibility violations', async () => {
    const { container } = renderSecurity();
    expect(await axe(container)).toHaveNoViolations();
  });
});
