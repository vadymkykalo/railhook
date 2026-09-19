import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * The app's public URL surface, derived once.
 *
 * Two scripts need this list and they must not disagree: `generate-sitemap.mjs` publishes it
 * to crawlers and `prerender.mjs` renders it to static HTML. A URL in one and not the other is
 * either a page nobody can find or a promise in the sitemap that resolves to an empty shell.
 *
 * The docs are not here. They are a separate static site (`railhook-docs/`) served at /docs/
 * from the same image, already HTML, with a sitemap of their own at /docs/sitemap-index.xml
 * that `public/robots.txt` points crawlers at.
 */

/** The marketing pages, with the priority they deserve relative to each other. */
const MARKETING = [
  { path: '/', priority: '1.0', changefreq: 'weekly' },
  // The free plan, the self-hosted promise and what the alternatives cost.
  { path: '/pricing', priority: '0.8', changefreq: 'monthly' },
  // The free webhook tester: a tool people search for, and a first use before signing up.
  { path: '/tester', priority: '0.8', changefreq: 'monthly' },
  // The signature verifier: the other free tool, for "validate a Stripe webhook signature".
  { path: '/tools/webhook-signature', priority: '0.8', changefreq: 'monthly' },
  // Read before trusting a vendor with webhooks: what protects the data, who builds it, and
  // how actively (the release history, rebuilt from CHANGELOG.md on every build).
  { path: '/security', priority: '0.6', changefreq: 'monthly' },
  { path: '/about', priority: '0.5', changefreq: 'monthly' },
  { path: '/changelog', priority: '0.6', changefreq: 'weekly' },
  // The blog index. The posts themselves are appended below, from the content directory.
  { path: '/blog', priority: '0.7', changefreq: 'weekly' },
  { path: '/contact', priority: '0.5', changefreq: 'monthly' },
  // Linked from the registration form and from Google's consent screen, which requires both.
  { path: '/privacy', priority: '0.3', changefreq: 'yearly' },
  { path: '/terms', priority: '0.3', changefreq: 'yearly' },
];

/**
 * The posts, from the directories under `src/content/blog/`.
 *
 * Enumerated rather than listed by hand: a post is a directory with an `en.md` in it, and a
 * slug that is in the app but not here would be a page no crawler is told about and no
 * prerender renders — which is the failure this whole module exists to prevent. `src/lib/blog.ts`
 * globs the same directory for the pages themselves.
 *
 * Newest first, by the `date` in the English file's front matter, so the sitemap lists them in
 * the same order the index does. The front matter is read with the same parser the app uses.
 */
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

/** Every public route, in the order a sitemap should list them. */
export function publicRoutes() {
  return [...MARKETING, ...blogRoutes()];
}

/**
 * Whether a path is the blog's: its index, a post, or anything else under /blog/.
 *
 * The blog is railhook.io's own content and off unless a deployment turns it on (BLOG_ENABLED),
 * so the sitemap is written twice — with the blog and without it — and nginx serves the one
 * that matches the container's setting.
 */
export function isBlogPath(path) {
  return path === '/blog' || path.startsWith('/blog/');
}
