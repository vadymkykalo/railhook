const GUARD_KEY = 'railhook:stale-chunk-reload';
/** Long enough to cover one reload's round trip; short enough that a later deploy gets its own. */
const GUARD_WINDOW_MS = 60_000;

function recentlyReloaded(): boolean {
  try {
    const at = Number(sessionStorage.getItem(GUARD_KEY));
    return Number.isFinite(at) && at > 0 && Date.now() - at < GUARD_WINDOW_MS;
  } catch {
    // No sessionStorage (private mode, blocked site data): without a guard, never reload.
    return true;
  }
}

/** False when it reloaded moments ago: the chunk is really missing and another reload would loop. */
export function reloadOnceForStaleChunk(reload: () => void = () => window.location.reload()): boolean {
  if (recentlyReloaded()) return false;
  try {
    sessionStorage.setItem(GUARD_KEY, String(Date.now()));
  } catch {
    return false;
  }
  reload();
  return true;
}

export function isStaleChunkError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  return /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Unable to preload CSS/i.test(message);
}

/** A tab on the previous build asks for chunks a deploy removed; reloading fetches the new ones. */
export function installStaleChunkReload(reload?: () => void): () => void {
  const onPreloadError = (event: Event) => {
    if (reloadOnceForStaleChunk(reload)) event.preventDefault();
  };
  window.addEventListener('vite:preloadError', onPreloadError);
  return () => window.removeEventListener('vite:preloadError', onPreloadError);
}
