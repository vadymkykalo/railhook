#!/usr/bin/env node
/**
 * Derives public/sitemap.xml from the routes the app actually serves.
 *
 * The public surface is the landing page and contact, listed once in
 * `scripts/public-routes.mjs`. The docs are a separate site with a sitemap of their own
 * (/docs/sitemap-index.xml), so they are not repeated here.
 *
 * Admin routes are excluded on purpose: they are behind auth, they render
 * nothing to a crawler, and listing them only invites requests.
 *
 * `scripts/prerender.mjs` reads the same module, so a URL in the sitemap is a URL that was
 * rendered to static HTML.
 *
 * Two files, because the blog is off unless a deployment turns it on (BLOG_ENABLED):
 * `sitemap.xml` lists everything, the blog included, and `sitemap-without-blog.xml` lists the
 * rest. nginx serves one or the other as /sitemap.xml, from the container's setting — a crawler
 * is never sent to a post that answers 404.
 *
 *   npm run seo:sitemap             regenerate both (commit the result)
 *   npm run seo:sitemap -- --check  fail if either committed copy is stale
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

import { isBlogPath, publicRoutes } from './public-routes.mjs';

const here = dirname(fileURLToPath(import.meta.url));

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

function sitemap(entries) {
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...entries.map(urlEntry),
    '</urlset>',
    '',
  ].join('\n');
}

const routes = publicRoutes();
const FILES = [
  { name: 'sitemap.xml', entries: routes },
  { name: 'sitemap-without-blog.xml', entries: routes.filter((route) => !isBlogPath(route.path)) },
];

const check = process.argv.includes('--check');
let stale = false;
for (const { name, entries } of FILES) {
  const out = resolve(here, '../public', name);
  const xml = sitemap(entries);
  if (!check) {
    writeFileSync(out, xml);
    console.log(`Wrote public/${name} (${entries.length} URLs).`);
    continue;
  }
  let current = '';
  try {
    current = readFileSync(out, 'utf8');
  } catch {
    console.error(`public/${name} is missing. Run: npm run seo:sitemap`);
    stale = true;
    continue;
  }
  if (current !== xml) {
    console.error(`public/${name} is stale. Run: npm run seo:sitemap`);
    stale = true;
    continue;
  }
  console.log(`${name} is up to date (${entries.length} URLs).`);
}
if (stale) process.exit(1);
