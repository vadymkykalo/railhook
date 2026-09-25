import { MutationCache, QueryClient } from '@tanstack/react-query';
import { showApiError, wasErrorReported } from './toast';

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: {
      handlesError?: boolean;
    };
  }
}

/** Waits a macrotask so the caller's catch/onError runs first; silent if showApiError already reported it. */
function reportUnhandledMutationError(error: unknown, mutation: { options: { onError?: unknown; meta?: { handlesError?: boolean } } }) {
  if (mutation.options.onError || mutation.options.meta?.handlesError) return;
  setTimeout(() => {
    if (!wasErrorReported(error)) showApiError(error, 'toast.errors.unhandledMutation');
  }, 0);
}

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
