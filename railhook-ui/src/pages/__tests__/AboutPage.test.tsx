import { afterEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import '../../i18n';
import i18n from '../../i18n';
import en from '../../i18n/locales/en.json';
import uk from '../../i18n/locales/uk.json';
import { renderPage } from '../../test/renderPage';
import AboutPage from '../AboutPage';
import { REPO_URL } from '../landing/plans';

/**
 * Who builds Railhook and why, in facts only: the maintainer, the licence, the stack and where
 * the work happens. No invented team, customers, location or funding.
 */
function renderAbout() {
  return renderPage(<AboutPage />, {
    path: '/about',
    initialEntry: '/about',
    auth: { user: null, token: null, isAuthenticated: false },
  });
}

afterEach(async () => {
  await i18n.changeLanguage('en');
});

describe('AboutPage', () => {
  it('names the page for search: title, description and canonical', () => {
    renderAbout();
    expect(document.title).toBe(en.meta.about.title);
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toMatch(/\/about$/);
  });

  it('names the maintainer and links to him on GitHub', () => {
    renderAbout();
    expect(document.body.textContent).toContain('Vadym Kykalo');
    expect(screen.getByRole('link', { name: en.about.who.github })).toHaveAttribute('href', 'https://github.com/vadymkykalo');
  });

  it('states the licence and links to the source', () => {
    renderAbout();
    expect(document.body.textContent).toMatch(/MIT/);
    expect(screen.getAllByRole('link').some((a) => a.getAttribute('href') === REPO_URL)).toBe(true);
  });

  it('lists the stack Railhook runs on', () => {
    renderAbout();
    const text = document.body.textContent ?? '';
    for (const part of ['Java', 'Spring Boot', 'PostgreSQL', 'Kafka', 'Redis']) expect(text).toContain(part);
  });

  it('links to the docs, the changelog and the contact page', () => {
    renderAbout();
    const hrefs = screen.getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(expect.arrayContaining(['/docs/', '/changelog', '/contact']));
  });

  it('switches language', async () => {
    await i18n.changeLanguage('uk');
    renderAbout();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(uk.about.title);
  });

  it('has no detectable accessibility violations', async () => {
    const { container } = renderAbout();
    expect(await axe(container)).toHaveNoViolations();
  });
});
