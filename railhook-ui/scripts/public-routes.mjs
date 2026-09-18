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
  { path: '/contact', priority: '0.5', changefreq: 'monthly' },
  // Linked from the registration form and from Google's consent screen, which requires both.
  { path: '/privacy', priority: '0.3', changefreq: 'yearly' },
  { path: '/terms', priority: '0.3', changefreq: 'yearly' },
];

/** Every public route, in the order a sitemap should list them. */
export function publicRoutes() {
  return [...MARKETING];
}
