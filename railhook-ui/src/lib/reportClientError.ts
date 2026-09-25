import { clientErrorsApi } from '../api/clientErrors.api';
import { http } from '../api/http';

/** Must never throw or retry: it runs on a page that has already broken. */

const MAX_REPORTS_PER_PAGE = 10;

const seen = new Set<string>();
let sent = 0;

/** Keyed on message + component, not stack: stacks differ between throws of the same bug. */
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
    // Deliberately silent: the console already has the original error.
  }
}

export function resetClientErrorReporterForTests(): void {
  seen.clear();
  sent = 0;
}
