import { describe, it, expect } from 'vitest';
import en from '../locales/en.json';
import uk from '../locales/uk.json';

// Locale-parity guard: the two locale files currently have zero key
// drift in either direction. This test locks that in — a key added to one
// locale without its translation in the other should fail CI immediately,
// rather than surfacing later as a silent fallback-to-English string (or a
// raw i18n key) in production.

/** Recursively collects every leaf key path, e.g. "deliveries.filters.status". */
function collectKeyPaths(value: unknown, prefix = ''): string[] {
  if (value === null || typeof value !== 'object') {
    return [prefix];
  }
  return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) =>
    collectKeyPaths(child, prefix ? `${prefix}.${key}` : key)
  );
}

describe('locale parity (en.json vs uk.json)', () => {
  const enKeys = new Set(collectKeyPaths(en));
  const ukKeys = new Set(collectKeyPaths(uk));

  it('has at least one key (sanity check the fixtures loaded)', () => {
    expect(enKeys.size).toBeGreaterThan(100);
    expect(ukKeys.size).toBeGreaterThan(100);
  });

  it('every en.json key exists in uk.json', () => {
    const missingFromUk = [...enKeys].filter((key) => !ukKeys.has(key)).sort();
    expect(missingFromUk).toEqual([]);
  });

  it('every uk.json key exists in en.json', () => {
    const missingFromEn = [...ukKeys].filter((key) => !enKeys.has(key)).sort();
    expect(missingFromEn).toEqual([]);
  });

  it('has the same total key count in both locales', () => {
    expect(ukKeys.size).toBe(enKeys.size);
  });
});
