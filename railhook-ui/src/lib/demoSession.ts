import type { CurrentUserResponse } from '../types/api.types';

/** No refresh cookie exists for a demo token, so sessionStorage is what survives a reload. */

const KEY = 'railhook_demo_session';

export interface DemoSession {
  token: string;
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
