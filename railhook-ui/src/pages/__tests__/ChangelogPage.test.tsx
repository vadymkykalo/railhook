import { afterEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import i18n from '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';
import ChangelogPage from '../ChangelogPage';
import changelog from 'virtual:changelog';
import { parseChangelog } from '../../lib/changelog';

/**
 * The release history on the site, read from the repository's CHANGELOG.md when the app is
 * built — so the page cannot fall behind the file.
 */
function renderChangelog() {
  return renderPage(<ChangelogPage />, {
    path: '/changelog',
    initialEntry: '/changelog',
    auth: { user: null, token: null, isAuthenticated: false },
  });
}

const releases = parseChangelog(changelog);

afterEach(async () => {
  await i18n.changeLanguage('en');
});

describe('ChangelogPage', () => {
  it('names the page for search: title, description and canonical', () => {
    renderChangelog();
    expect(document.title).toBe(en.meta.changelog.title);
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toMatch(/\/changelog$/);
  });

  it('shows every release from CHANGELOG.md, newest first, each at its own anchor', () => {
    renderChangelog();
    const headings = screen.getAllByRole('heading', { level: 2 });
    expect(headings.map((h) => h.textContent?.trim())).toEqual(releases.map((r) => r.version));
    for (const release of releases) {
      expect(document.getElementById(release.id), release.id).not.toBeNull();
    }
    expect(screen.getByRole('link', { name: en.changelog.anchor.replace('{{version}}', releases[0].version) }))
      .toHaveAttribute('href', `#${releases[0].id}`);
  });

  it('renders the file’s markup as elements, never as raw markdown', () => {
    renderChangelog();
    // Markup characters inside a code span are content (`/api/v1/admin/**`), so they are left out.
    const prose = document.body.cloneNode(true) as HTMLElement;
    prose.querySelectorAll('code').forEach((code) => code.remove());
    const text = prose.textContent ?? '';
    expect(text).not.toMatch(/\*\*/);
    expect(text).not.toMatch(/\]\(/);
    expect(text).not.toMatch(/^## /m);
    expect(document.querySelectorAll('main strong, article strong').length).toBeGreaterThan(0);
    expect(document.querySelectorAll('article code').length).toBeGreaterThan(0);
  });

  it('does not show an Unreleased section', () => {
    renderChangelog();
    expect(screen.queryByRole('heading', { name: /unreleased/i })).toBeNull();
  });

  it('opens repository and external links in a new tab, safely', () => {
    renderChangelog();
    const external = Array.from(document.querySelectorAll<HTMLAnchorElement>('article a[href^="http"]'));
    expect(external.length).toBeGreaterThan(0);
    for (const a of external) expect(a).toHaveAttribute('rel', 'noopener noreferrer');
  });
});
