/**
 * The visitor's answer to the cookie notice, kept in this browser only.
 *
 * `public/analytics.js` reads the same key before loading the Cloudflare beacon, which is why it
 * is a plain string under a fixed name rather than anything richer: that script runs before the
 * app and cannot import this module.
 */
export type Consent = 'accepted' | 'declined';

export const CONSENT_KEY = 'railhook.consent';

export function readConsent(): Consent | null {
  try {
    const value = localStorage.getItem(CONSENT_KEY);
    return value === 'accepted' || value === 'declined' ? value : null;
  } catch {
    return null;
  }
}

export function saveConsent(value: Consent): void {
  try {
    localStorage.setItem(CONSENT_KEY, value);
  } catch {
    // Storage blocked: the notice comes back next visit, which is the honest outcome.
  }
}
