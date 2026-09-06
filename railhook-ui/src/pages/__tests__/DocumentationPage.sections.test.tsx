import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import DocumentationPage from '../DocumentationPage';
import { GUIDE_SECTIONS, sectionPath } from '../docs/sections';

/**
 * A guide is registered in two files: `sections.ts` puts it in the sidebar, and
 * `DocumentationPage` decides what to render for it. Nothing ties the two
 * together, so adding a section to one and not the other produces a nav link
 * that opens an empty content area — no error, no blank-page fallback, just a
 * heading and nothing under it. That is the failure this asserts against, and
 * it is the one that actually happens when guides are added in a batch.
 */
describe('every guide in the sidebar renders something', () => {
  it.each(GUIDE_SECTIONS.map((s) => [s.id, sectionPath(s.id)] as const))(
    '%s',
    async (_id, path) => {
      renderPage(<DocumentationPage />, { path: '/docs/:sectionId?', initialEntry: path });

      // Every guide opens with a DocsTitle, which is the article's only h1.
      const heading = await screen.findByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
      expect(heading.textContent?.trim()).not.toBe('');

      // A title alone would pass on a page whose body failed to wire up, so
      // require some prose under it too.
      const article = heading.closest('article') ?? heading.parentElement;
      expect(article?.textContent?.length ?? 0).toBeGreaterThan(200);
    },
  );
});
