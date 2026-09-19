import type { CurrentUserResponse } from '../types/api.types';

/**
 * The live demo's session, kept in this tab.
 *
 * A demo session is an access token and nothing else: no refresh cookie exists for it, so the
 * silent refresh that restores a real session on reload has nothing to present. Keeping the token
 * in sessionStorage is what lets a reload stay in the demo. It is scoped to the tab and ends with
 * it, and the token is worth little — read-only on the server, for half an hour.
 */

const KEY = 'railhook_demo_session';

export interface DemoSession {
  token: string;
  /** ISO instant the token stops working. */
  expiresAt: string;
  user: CurrentUserResponse;
}

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.sessionStorage : null;
  } catch {
    return null;
  }
}

export function saveDemoSession(session: DemoSession): void {
  try {
    storage()?.setItem(KEY, JSON.stringify(session));
  } catch {
    // A tab that cannot store simply loses the demo on reload.
  }
}

/** The demo session in this tab, or null when there is none or it has expired. */
export function readDemoSession(now: number = Date.now()): DemoSession | null {
  try {
    const raw = storage()?.getItem(KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as DemoSession;
    if (!session.token || !session.expiresAt || Date.parse(session.expiresAt) <= now) {
      clearDemoSession();
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export function clearDemoSession(): void {
  try {
    storage()?.removeItem(KEY);
  } catch {
    // Nothing to clear.
  }
}
