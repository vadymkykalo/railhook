import { MutationCache, QueryClient } from '@tanstack/react-query';
import { showApiError, wasErrorReported } from './toast';

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: {
      /** The caller shows the failure inline (not as a toast) and wants no fallback toast. */
      handlesError?: boolean;
    };
  }
}

/**
 * The net under mutation failures nobody reported.
 *
 * Call sites report their own failures — a `catch` around `mutateAsync`, or an `onError` passed
 * to `mutate` — and both run after the cache's onError. A fallback that toasted straight away
 * therefore doubled every one of those toasts. So the fallback waits a macrotask, by which time
 * the mutation has settled and the caller's catch or onError has run, and stays silent if
 * `showApiError` has already reported this very error object.
 */
function reportUnhandledMutationError(error: unknown, mutation: { options: { onError?: unknown; meta?: { handlesError?: boolean } } }) {
  if (mutation.options.onError || mutation.options.meta?.handlesError) return;
  setTimeout(() => {
    if (!wasErrorReported(error)) showApiError(error, 'toast.errors.unhandledMutation');
  }, 0);
}

/** The app's QueryClient, built by a function so its error handling can be tested. */
export function createQueryClient() {
  return new QueryClient({
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => reportUnhandledMutationError(error, mutation),
    }),
    defaultOptions: {
      queries: {
        staleTime: 5 * 60 * 1000,
        gcTime: 10 * 60 * 1000,
        retry: 1,
        refetchOnWindowFocus: true,
      },
    },
  });
}
