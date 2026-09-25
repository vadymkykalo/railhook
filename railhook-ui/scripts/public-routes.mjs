import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Shared by the sitemap and the prerender so they can't disagree. */

const MARKETING = [
  { path: '/', priority: '1.0', changefreq: 'weekly' },
  { path: '/pricing', priority: '0.8', changefreq: 'monthly' },
  { path: '/tester', priority: '0.8', changefreq: 'monthly' },
  { path: '/tools/webhook-signature', priority: '0.8', changefreq: 'monthly' },
  { path: '/blog', priority: '0.7', changefreq: 'weekly' },
  // Google's consent screen requires the privacy link.
  { path: '/privacy', priority: '0.3', changefreq: 'yearly' },
  { path: '/terms', priority: '0.3', changefreq: 'yearly' },
];

/** Enumerated from src/content/blog/, like the app, so no post is left unlisted. */
function blogRoutes() {
  const dir = resolve(dirname(fileURLToPath(import.meta.url)), '../src/content/blog');
  let entries = [];
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((entry) => entry.isDirectory() && existsSync(join(dir, entry.name, 'en.md')))
    .map((entry) => ({
      slug: entry.name,
      date: readFileSync(join(dir, entry.name, 'en.md'), 'utf8').match(/^date:\s*(\S+)\s*$/m)?.[1] ?? '',
    }))
    .sort((a, b) => b.date.localeCompare(a.date) || a.slug.localeCompare(b.slug))
    .map(({ slug }) => ({ path: `/blog/${slug}`, priority: '0.7', changefreq: 'monthly' }));
}

export function publicRoutes() {
  return [...MARKETING, ...blogRoutes()];
}

/** The blog is off unless BLOG_ENABLED, so the sitemap is written with and without it. */
export function isBlogPath(path) {
  return path === '/blog' || path.startsWith('/blog/');
}
