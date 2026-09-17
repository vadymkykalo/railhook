/** Where a sign-in lands when nothing asked for somewhere else. */
export const DEFAULT_SIGN_IN_DESTINATION = '/admin/dashboard';

/**
 * Same rule the API applies to a Google sign-in's returnTo: a path on this site, nothing a browser
 * would read as another host. Anything else is `fallback`.
 */
export function safeDestination(value: string | null | undefined, fallback = DEFAULT_SIGN_IN_DESTINATION): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return fallback;
  }
  return value;
}

const SIGNED_OUT_USER_KEY = 'railhook-signed-out-user';

/**
 * Notes who this tab was signed in as when the session ended. The sign-in screen is then handed the
 * page they were on, and a different person signing in next must not be taken into it.
 */
export function rememberSignedOutUser(userId: string | undefined): void {
  try {
    if (userId) sessionStorage.setItem(SIGNED_OUT_USER_KEY, userId);
  } catch {
    // Storage refused: the worst case is landing on the default page.
  }
}

/**
 * The page to open after signing in. An explicit `?redirect=` (an invite, a CLI approval) is for
 * whoever signs in. The page a session ended on is only for the same person: a different one would
 * be taken into a project of an organization they may not belong to.
 */
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
