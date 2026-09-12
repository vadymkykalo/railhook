import { clientErrorsApi } from '../api/clientErrors.api';
import { http } from '../api/http';

/**
 * Sends a failure the dashboard could not recover from back to this installation, where it is
 * written to the same logs as everything else. No third party is involved and no DSN is
 * configured: a self-hosted operator reads their own logs, and so do we.
 *
 * <p>Everything here follows from where it runs — on a page that has already broken:
 *
 * - It never throws and never rejects. A reporter that can fail is a second bug on top of the
 *   first one, and the user is already looking at an error screen.
 * - It never retries. The report is worth having, not worth insisting on.
 * - It reports each distinct failure once. A component that throws on every render would
 *   otherwise post on every frame, from every open tab.
 * - It gives up after a handful of distinct failures in one page life. Past that the page is
 *   not having a bug, it is coming apart, and the first few reports already say so.
 * - It stays quiet with no session, because the endpoint requires one and would only answer
 *   401 — the sign-in screen's own errors stay in the console.
 */

/** How many distinct failures one page life will report before it stops talking. */
const MAX_REPORTS_PER_PAGE = 10;

const seen = new Set<string>();
let sent = 0;

/**
 * What counts as "the same failure again".
 *
 * The message plus the component React blamed, deliberately not the JS stack: a stack differs
 * between two throws of the identical bug (different call paths, async boundaries, a minified
 * build numbering frames its own way), so keying on it would report the same broken component
 * once per render — the exact thing this is here to prevent. Two genuinely different bugs
 * sharing a message and a component is a collision worth accepting; the first report carries
 * the full stack either way.
 */
function fingerprint(message: string, componentStack: string | undefined): string {
  return `${message}::${(componentStack ?? '').split('\n')[1]?.trim() ?? ''}`;
}

function describe(error: unknown): { message: string; stack?: string } {
  if (error instanceof Error) {
    return { message: error.message || error.name, stack: error.stack };
  }
  return { message: `Non-Error thrown: ${String(error)}` };
}

export async function reportClientError(
  error: unknown,
  context?: { componentStack?: string },
): Promise<void> {
  try {
    if (!http.getToken()) {
      return;
    }
    if (sent >= MAX_REPORTS_PER_PAGE) {
      return;
    }

    const { message, stack } = describe(error);
    if (!message.trim()) {
      return;
    }

    const key = fingerprint(message, context?.componentStack);
    if (seen.has(key)) {
      return;
    }
    seen.add(key);
    sent++;

    await clientErrorsApi.report({
      message,
      stack,
      componentStack: context?.componentStack,
      url: window.location.href,
      release: __APP_VERSION__,
    });
  } catch {
    // Deliberately silent. The console already has the original error, which is the one worth
    // reading; a failed report about it is noise on a screen that is already apologising.
  }
}

/** Clears the per-page state. Tests only — a real page life ends with the page. */
export function resetClientErrorReporterForTests(): void {
  seen.clear();
  sent = 0;
}
