/** No consent to ask: only the sign-in cookie, and analytics are cookieless. */
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
