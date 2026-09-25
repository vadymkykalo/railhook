import { configuredSiteUrl } from './runtimeConfig';

/** Never a constant: a canonical naming another origin tells search engines to credit that site. */
export function siteUrl(): string {
  const raw = configuredSiteUrl() || (typeof window !== 'undefined' ? window.location.origin : '');
  return raw.replace(/\/+$/, '');
}
