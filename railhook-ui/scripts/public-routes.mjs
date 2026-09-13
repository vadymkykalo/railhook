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
  { path: '/pricing', priority: '0.9', changefreq: 'weekly' },
  { path: '/contact', priority: '0.5', changefreq: 'monthly' },
];

/** Every public route, in the order a sitemap should list them. */
export function publicRoutes() {
  return [...MARKETING];
}
