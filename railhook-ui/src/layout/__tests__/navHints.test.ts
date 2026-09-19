import { describe, expect, it } from 'vitest';
import en from '../../i18n/locales/en.json';
import uk from '../../i18n/locales/uk.json';
import { API_KEYS_TAB, PROJECT_SECTIONS, hintKeyOf, type NavEntry } from '../nav.config';

function lookup(messages: unknown, key: string): unknown {
  return key.split('.').reduce<unknown>(
    (node, part) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined),
    messages,
  );
}

/**
 * A project's rail and its tabs are where a newcomer decides where to click, and a label alone —
 * "Consumers", "Time Machine", "Failed Forwards" — does not say what is behind it.
 */
describe('navigation hints', () => {
  const entries: NavEntry[] = [
    ...PROJECT_SECTIONS.flatMap((section) => [section as NavEntry, ...section.tabs]),
    API_KEYS_TAB,
  ];

  it.each(entries.map((entry) => [entry.nameKey, entry] as const))('%s says what it is for, in both languages', (_, entry) => {
    const key = hintKeyOf(entry);
    expect(lookup(en, key), `${key} in en.json`).toEqual(expect.any(String));
    expect(lookup(uk, key), `${key} in uk.json`).toEqual(expect.any(String));
  });
});
