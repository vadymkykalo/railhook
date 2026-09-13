import { useEffect, useState } from 'react';
import { Toaster } from 'sonner';
import { isDarkApplied } from '../lib/theme';

const appliedTheme = () => (isDarkApplied() ? 'dark' : 'light');

/**
 * Sonner draws its own light palette unless it is told the theme, so on the dark theme toasts
 * arrived as pale slabs. It follows the class on <html> — what is applied, not what is stored —
 * and keeps following it when the reader flips the theme or the system preference changes.
 */
export default function ThemedToaster() {
  const [theme, setTheme] = useState<'light' | 'dark'>(appliedTheme);

  useEffect(() => {
    const observer = new MutationObserver(() => setTheme(appliedTheme()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    setTheme(appliedTheme());
    return () => observer.disconnect();
  }, []);

  return <Toaster position="top-right" richColors theme={theme} />;
}
