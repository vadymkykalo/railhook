import { useEffect, useState } from 'react';

/**
 * The value, but only once it has stopped changing for {@code delayMs}.
 *
 * <p>A search box fires on every keystroke; the query behind it should not.
 */
export function useDebounced<T>(value: T, delayMs = 400): T {
  const [settled, setSettled] = useState(value);

  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);

  return settled;
}
