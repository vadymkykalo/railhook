/**
 * The public origin this deployment is served from.
 *
 * Every absolute URL the app publishes about itself — the canonical link,
 * `og:url`, `og:image` — has to name an origin. Which origin is not knowable
 * at authoring time: the same build serves a hosted instance, a company's
 * internal dashboard and a laptop on port 8080.
 *
 * It used to be a constant pointing at a domain this project does not own.
 * That is not a cosmetic mistake: a `rel="canonical"` is an instruction to a
 * search engine to credit a different site, and every self-hosted install was
 * issuing it on every page.
 *
 * So: `VITE_SITE_URL` when the build declares one, and the browser's own
 * origin otherwise. The fallback is right far more often than any constant
 * could be, and it cannot name somebody else.
 *
 * Build-time rather than runtime because `scripts/prerender.mjs` drives a real
 * browser over `dist/` against a throwaway local server — without the variable
 * the prerendered HTML would bake `http://localhost:<port>` into the canonical
 * of the hosted site.
 */
/**
 * Origin with no trailing slash, so `${siteUrl()}${path}` is always well formed.
 *
 * The variable is read inside the function rather than hoisted to a module
 * constant. Vite inlines `import.meta.env.VITE_SITE_URL` either way, so the
 * bundle is identical — but a constant is captured once at module load, which
 * would make the two branches untestable in the same run.
 */
export function siteUrl(): string {
  const configured = import.meta.env.VITE_SITE_URL?.trim();
  const raw = configured || (typeof window !== 'undefined' ? window.location.origin : '');
  return raw.replace(/\/+$/, '');
}
