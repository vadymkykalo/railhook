import { describe, expect, it } from 'vitest';
import { docsUrl } from '../docsUrl';

describe('docsUrl', () => {
  it('addresses the docs home with a trailing slash', () => {
    expect(docsUrl('en')).toBe('/docs/');
  });

  it('addresses a page under the English root', () => {
    expect(docsUrl('en', 'outgoing/retries')).toBe('/docs/outgoing/retries/');
  });

  it('sends a Ukrainian reader to the Ukrainian copy', () => {
    expect(docsUrl('uk', 'outgoing/retries')).toBe('/docs/uk/outgoing/retries/');
    expect(docsUrl('uk')).toBe('/docs/uk/');
  });

  it('treats a regional tag as its language', () => {
    expect(docsUrl('uk-UA', 'tools/cli')).toBe('/docs/uk/tools/cli/');
    expect(docsUrl('en-GB', 'tools/cli')).toBe('/docs/tools/cli/');
  });

  it('falls back to English for a language the docs are not written in', () => {
    expect(docsUrl('de', 'tools/cli')).toBe('/docs/tools/cli/');
    expect(docsUrl(undefined)).toBe('/docs/');
  });

  it('tolerates slashes around the slug', () => {
    expect(docsUrl('en', '/outgoing/replay/')).toBe('/docs/outgoing/replay/');
  });
});
