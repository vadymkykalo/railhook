import { useEffect, useState } from 'react';
import { Toaster } from 'sonner';
import { isDarkApplied } from '../lib/theme';

const appliedTheme = () => (isDarkApplied() ? 'dark' : 'light');

/** Sonner draws its own light palette unless told the applied theme. */
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
