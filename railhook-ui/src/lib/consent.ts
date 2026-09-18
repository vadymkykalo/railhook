/**
 * Whether this browser has already seen the cookie notice, kept in this browser only.
 *
 * The notice asks nothing: the site sets only the sign-in cookie, and its analytics are
 * cookieless, so there is nothing to consent to. It says so once, and this is how it knows.
 */
export const NOTICE_KEY = 'railhook.cookie-notice';

export function noticeSeen(): boolean {
  try {
    return localStorage.getItem(NOTICE_KEY) === 'seen';
  } catch {
    return false;
  }
}

export function markNoticeSeen(): void {
  try {
    localStorage.setItem(NOTICE_KEY, 'seen');
  } catch {
    // Storage blocked: the notice comes back next visit, which is harmless.
  }
}
