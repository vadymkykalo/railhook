import { useCallback, useEffect, useRef, useState } from 'react';

/** Resets on unmount too, so a dialog closed mid-copy does not set state on a gone component. */
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
