import '@testing-library/jest-dom';
import { expect } from 'vitest';
import { toHaveNoViolations } from 'jest-axe';
import i18n from '../i18n';
import en from '../i18n/locales/en.json';
import uk from '../i18n/locales/uk.json';

expect.extend(toHaveNoViolations);

// Preloaded so page tests skip the lazy locale import and its Suspense tick.
i18n.addResourceBundle('en', 'translation', en, true, true);
i18n.addResourceBundle('uk', 'translation', uk, true, true);

// jsdom lacks both, and React Flow measures its canvas with them on mount.
class NoopResizeObserver implements ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= NoopResizeObserver;
globalThis.DOMMatrixReadOnly ??= class {
  m22 = 1;
  constructor(_transform?: string) {}
} as unknown as typeof DOMMatrixReadOnly;

// jsdom has no matchMedia; report "not a phone", the viewport page tests assume.
globalThis.matchMedia ??= ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addEventListener: () => {},
  removeEventListener: () => {},
  addListener: () => {},
  removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof matchMedia;
