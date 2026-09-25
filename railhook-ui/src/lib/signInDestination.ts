export const DEFAULT_SIGN_IN_DESTINATION = '/admin/dashboard';

/** Same rule the API applies to a Google sign-in's returnTo: a same-site path only. */
export function safeDestination(value: string | null | undefined, fallback = DEFAULT_SIGN_IN_DESTINATION): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return fallback;
  }
  return value;
}

const SIGNED_OUT_USER_KEY = 'railhook-signed-out-user';

/** A different person signing in next must not be taken to the previous user's page. */
export function rememberSignedOutUser(userId: string | undefined): void {
  try {
    if (userId) sessionStorage.setItem(SIGNED_OUT_USER_KEY, userId);
  } catch {
    // Storage refused: the worst case is landing on the default page.
  }
}

/** An explicit ?redirect= is for anyone; the page a session ended on only for the same user. */
export function destinationAfterSignIn(
  { redirect, from, userId }: { redirect: string | null; from: unknown; userId: string | undefined },
  fallback: string,
): string {
  let previousUserId: string | null = null;
  try {
    previousUserId = sessionStorage.getItem(SIGNED_OUT_USER_KEY);
    sessionStorage.removeItem(SIGNED_OUT_USER_KEY);
  } catch {
    // As above.
  }
  if (redirect) {
    return safeDestination(redirect, fallback);
  }
  if (typeof from === 'string' && (!previousUserId || previousUserId === userId)) {
    return safeDestination(from, fallback);
  }
  return fallback;
}
