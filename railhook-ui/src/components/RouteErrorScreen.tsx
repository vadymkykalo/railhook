import { useEffect } from 'react';
import { isRouteErrorResponse, useRouteError } from 'react-router-dom';
import { ErrorFallback } from './ErrorBoundary';
import { isStaleChunkError, reloadOnceForStaleChunk } from '../lib/staleChunkReload';

/**
 * The router's `errorElement`. Without one, a route that failed to load — most often a lazy page
 * whose chunk the latest deploy replaced — rendered React Router's bare "Unexpected Application
 * Error!", on the public pages too. A stale chunk gets one reload; anything else, or a reload that
 * did not help, gets the app's own error screen.
 */
export default function RouteErrorScreen() {
  const error = useRouteError();
  const stale = isStaleChunkError(error);

  useEffect(() => {
    if (stale) reloadOnceForStaleChunk();
  }, [stale]);

  const asError = error instanceof Error
    ? error
    : isRouteErrorResponse(error)
      ? new Error(`${error.status} ${error.statusText}`)
      : null;
  return <ErrorFallback error={asError} variant="app" />;
}
