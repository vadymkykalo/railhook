import { describe, expect, it } from 'vitest';

import { DEFAULT_TIMEOUT_MS, EXPORT_TIMEOUT_MS } from '../http';

/**
 * A request with no timeout never settles when the backend stops answering, so the page it
 * belongs to spins forever: no error state, nothing for react-query to catch, no way out but
 * a reload. The client shipped without one.
 */
describe('http client timeouts', () => {
  it('has a finite default', () => {
    expect(DEFAULT_TIMEOUT_MS).toBeGreaterThan(0);
    expect(Number.isFinite(DEFAULT_TIMEOUT_MS)).toBe(true);
  });

  it('gives exports longer than an ordinary call, since they stream a whole dataset', () => {
    expect(EXPORT_TIMEOUT_MS).toBeGreaterThan(DEFAULT_TIMEOUT_MS);
  });

  it('keeps the default short enough to surface a hung backend while someone is still watching', () => {
    expect(DEFAULT_TIMEOUT_MS).toBeLessThanOrEqual(60_000);
  });
});
