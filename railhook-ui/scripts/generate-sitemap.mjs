#!/usr/bin/env node
/** Two sitemaps: nginx serves the one matching BLOG_ENABLED. */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

import { isBlogPath, publicRoutes } from './public-routes.mjs';

const here = dirname(fileURLToPath(import.meta.url));

/** Sitemaps need absolute URLs; nginx replaces the placeholder origin when serving. */
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
