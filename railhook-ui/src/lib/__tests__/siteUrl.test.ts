import { afterEach, describe, expect, it } from 'vitest';

import { siteUrl } from '../siteUrl';

/** Unconfigured must resolve to this deployment, never a guessed domain. */
describe('siteUrl', () => {
  afterEach(() => {
    delete window.__RAILHOOK__;
  });

  it('uses the origin the page is served from when nothing is configured', () => {
    window.__RAILHOOK__ = { siteUrl: '' };

    expect(siteUrl()).toBe(window.location.origin);
  });

  it('uses the origin the page is served from when there is no runtime config at all', () => {
    expect(siteUrl()).toBe(window.location.origin);
  });

  it("prefers the container's configured origin, because prerender has no meaningful window", () => {
    window.__RAILHOOK__ = { siteUrl: 'https://hooks.example.com' };

    expect(siteUrl()).toBe('https://hooks.example.com');
  });

  it('strips trailing slashes so a path can be appended directly', () => {
    window.__RAILHOOK__ = { siteUrl: 'https://hooks.example.com//' };

    expect(`${siteUrl()}/pricing`).toBe('https://hooks.example.com/pricing');
  });

  it('ignores a value that is only whitespace rather than emitting a bare path', () => {
    window.__RAILHOOK__ = { siteUrl: '   ' };

    expect(siteUrl()).toBe(window.location.origin);
  });
});
