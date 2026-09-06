import { afterEach, describe, expect, it, vi } from 'vitest';

import { siteUrl } from '../siteUrl';

/**
 * This used to be a constant naming a domain the project does not own, so every
 * self-hosted install published a `rel="canonical"` crediting a stranger's site
 * and an `og:image` hosted by them. The two cases below are what stops that
 * coming back: unconfigured must resolve to *this* deployment, never a guess.
 */
describe('siteUrl', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('uses the origin the page is served from when nothing is configured', () => {
    vi.stubEnv('VITE_SITE_URL', '');

    expect(siteUrl()).toBe(window.location.origin);
  });

  it('prefers the configured origin, because prerender has no meaningful window', () => {
    vi.stubEnv('VITE_SITE_URL', 'https://hooks.example.com');

    expect(siteUrl()).toBe('https://hooks.example.com');
  });

  it('strips trailing slashes so a path can be appended directly', () => {
    vi.stubEnv('VITE_SITE_URL', 'https://hooks.example.com//');

    expect(`${siteUrl()}/pricing`).toBe('https://hooks.example.com/pricing');
  });

  it('ignores a value that is only whitespace rather than emitting a bare path', () => {
    vi.stubEnv('VITE_SITE_URL', '   ');

    expect(siteUrl()).toBe(window.location.origin);
  });
});
