import { test } from 'node:test';
import assert from 'node:assert/strict';

import { SITE_PLACEHOLDER, socialImageHead, pageFileFor, lastmodFor } from './seo.mjs';

/*
 * Guide pages declared `twitter:card summary_large_image` and named no image, so a link to the
 * docs unfurled as a bare title in every chat and card preview. And the sitemap carried no
 * <lastmod>, so a crawler had no signal that a page had changed since it last came by.
 */

test('every page names an absolute social image on the placeholder origin nginx rewrites', () => {
  const metas = Object.fromEntries(socialImageHead.map((e) => [e.attrs.property ?? e.attrs.name, e.attrs.content]));
  assert.equal(metas['og:image'], `${SITE_PLACEHOLDER}/og-image.png`);
  assert.equal(metas['twitter:image'], `${SITE_PLACEHOLDER}/og-image.png`);
  for (const entry of socialImageHead) assert.equal(entry.tag, 'meta');
});

test('a sitemap URL maps back to the page file it was built from, in either language', () => {
  assert.match(pageFileFor(`${SITE_PLACEHOLDER}/docs/start/quickstart/`), /src\/content\/docs\/start\/quickstart\.mdx?$/);
  assert.match(pageFileFor(`${SITE_PLACEHOLDER}/docs/uk/start/quickstart/`), /src\/content\/docs\/uk\/start\/quickstart\.mdx?$/);
  assert.match(pageFileFor(`${SITE_PLACEHOLDER}/docs/`), /src\/content\/docs\/index\.mdx?$/);
  assert.equal(pageFileFor(`${SITE_PLACEHOLDER}/docs/api-reference/`), undefined);
});

test('lastmod is a real date for a page file, and the build date for a page with none', () => {
  const page = lastmodFor(`${SITE_PLACEHOLDER}/docs/start/quickstart/`);
  assert.ok(page instanceof Date && !Number.isNaN(page.getTime()));
  const built = new Date('2026-09-13T00:00:00Z');
  assert.equal(lastmodFor(`${SITE_PLACEHOLDER}/docs/api-reference/`, built), built);
});
