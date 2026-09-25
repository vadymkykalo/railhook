import { useEffect } from 'react';
import { isRouteErrorResponse, useRouteError } from 'react-router-dom';
import { ErrorFallback } from './ErrorBoundary';
import { isStaleChunkError, reloadOnceForStaleChunk } from '../lib/staleChunkReload';

/** Without an errorElement a stale lazy chunk showed React Router's bare error page. */
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
