import '@testing-library/jest-dom';
import { expect } from 'vitest';
import { toHaveNoViolations } from 'jest-axe';
import i18n from '../i18n';
import en from '../i18n/locales/en.json';
import uk from '../i18n/locales/uk.json';

expect.extend(toHaveNoViolations);

// Production loads each locale via a dynamic import() the first time it's
// needed (see src/i18n) so useTranslation() can suspend on first render or on
// a language switch. renderPage() wraps in a <Suspense> boundary for that
// case, but preloading both bundles synchronously here keeps ordinary page
// tests from paying an extra async tick (and a Suspense fallback flash) on
// every render() call.
i18n.addResourceBundle('en', 'translation', en, true, true);
i18n.addResourceBundle('uk', 'translation', uk, true, true);

// jsdom implements neither, and React Flow measures its canvas with both on mount. Without
// them the workflow builder throws during render rather than rendering an empty canvas, so a
// test of that page would only ever be testing the absence of a polyfill.
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

// jsdom has no matchMedia, and code that asks whether it is on a phone — the contact sheet locks
// the page behind it there, and only there — would otherwise throw on mount instead of rendering.
// Reports "not a phone", which is the viewport every page test assumes.
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
