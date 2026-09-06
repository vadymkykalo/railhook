import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Copies text and reports, for a couple of seconds, that it worked — the tick a button shows
 * before going back to its icon.
 *
 * <p>The flag resets on unmount as well as on the timer, so a dialog closed mid-copy does not set
 * state on a component that is gone.
 */
export function useCopyToClipboard(resetAfterMs = 2000) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  const copy = useCallback(async (value: string): Promise<boolean> => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => setCopied(false), resetAfterMs);
      return true;
    } catch {
      return false;
    }
  }, [resetAfterMs]);

  return { copied, copy };
}
