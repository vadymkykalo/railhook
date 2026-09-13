import { configuredSiteUrl } from './runtimeConfig';

/**
 * The public origin this deployment is served from.
 *
 * Every absolute URL the app publishes about itself — the canonical link,
 * `og:url`, `og:image` — has to name an origin. Which origin is not knowable
 * at authoring time: the same image serves a hosted instance, a company's
 * internal dashboard and a laptop on port 8080.
 *
 * It used to be a constant pointing at a domain this project does not own.
 * That is not a cosmetic mistake: a `rel="canonical"` is an instruction to a
 * search engine to credit a different site, and every self-hosted install was
 * issuing it on every page.
 *
 * So: the container's configured origin (`window.__RAILHOOK__.siteUrl`) when it
 * declares one, and the browser's own origin otherwise. The fallback is right far
 * more often than any constant could be, and it cannot name somebody else.
 *
 * The prerender sets the configured origin to the placeholder nginx rewrites, so
 * the static HTML never freezes the throwaway local server's address.
 *
 * Origin with no trailing slash, so `${siteUrl()}${path}` is always well formed.
 */
export function siteUrl(): string {
  const raw = configuredSiteUrl() || (typeof window !== 'undefined' ? window.location.origin : '');
  return raw.replace(/\/+$/, '');
}
