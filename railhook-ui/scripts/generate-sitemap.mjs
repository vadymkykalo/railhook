#!/usr/bin/env node
/**
 * Derives public/sitemap.xml from the routes the app actually serves.
 *
 * The public surface is the landing page, pricing and contact, listed once in
 * `scripts/public-routes.mjs`. The docs are a separate site with a sitemap of their own
 * (/docs/sitemap-index.xml), so they are not repeated here.
 *
 * Admin routes are excluded on purpose: they are behind auth, they render
 * nothing to a crawler, and listing them only invites requests.
 *
 * `scripts/prerender.mjs` reads the same module, so a URL in the sitemap is a URL that was
 * rendered to static HTML.
 *
 *   npm run seo:sitemap             regenerate (commit the result)
 *   npm run seo:sitemap -- --check  fail if the committed copy is stale
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

import { publicRoutes } from './public-routes.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(here, '../public/sitemap.xml');

/**
 * A sitemap has to carry absolute URLs — the spec allows nothing else — but the
 * image is built once for every deployment and cannot know its origin.
 *
 * So the committed copy names the placeholder origin, and nginx replaces it with
 * the container's RAILHOOK_SITE_URL when serving the file — the same substitution
 * the HTML and robots.txt get. `.invalid` is reserved: if the placeholder ever
 * escaped, it would send a crawler nowhere rather than to a stranger's site.
 */
const SITE = 'https://site-url.railhook.invalid';

function urlEntry({ path, priority, changefreq }) {
  return [
    '  <url>',
    `    <loc>${SITE}${path}</loc>`,
    `    <changefreq>${changefreq}</changefreq>`,
    `    <priority>${priority}</priority>`,
    '  </url>',
  ].join('\n');
}

const entries = publicRoutes();

const xml = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
  ...entries.map(urlEntry),
  '</urlset>',
  '',
].join('\n');

if (process.argv.includes('--check')) {
  let current = '';
  try {
    current = readFileSync(OUT, 'utf8');
  } catch {
    console.error('public/sitemap.xml is missing. Run: npm run seo:sitemap');
    process.exit(1);
  }
  if (current !== xml) {
    console.error('public/sitemap.xml is stale. Run: npm run seo:sitemap');
    process.exit(1);
  }
  console.log(`sitemap.xml is up to date (${entries.length} URLs).`);
} else {
  writeFileSync(OUT, xml);
  console.log(`Wrote public/sitemap.xml (${entries.length} URLs).`);
}
