import { useState } from 'react';
import { Moon, Sun } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { isDarkApplied, toggleTheme } from '../lib/theme';

interface ThemeToggleProps {
  variant?: 'icon' | 'full';
  className?: string;
}

export default function ThemeToggle({ variant = 'icon', className }: ThemeToggleProps) {
  const { t } = useTranslation();
  const [isDark, setIsDark] = useState(() => typeof document !== 'undefined' && isDarkApplied());

  const toggle = () => setIsDark(toggleTheme() === 'dark');

  if (variant === 'full') {
    return (
      <button
        type="button"
        onClick={toggle}
        className={className ?? 'flex items-center gap-2 px-3 py-2 text-[13px] font-medium text-muted-foreground hover:text-foreground transition-colors rounded-lg hover:bg-accent w-full'}
        title={t('nav.toggleTheme')}
      >
        {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
        <span>{t(isDark ? 'nav.lightMode' : 'nav.darkMode')}</span>
      </button>
    );
  }

  return (
    <button
      type="button"
      onClick={(e) => { e.preventDefault(); e.stopPropagation(); toggle(); }}
      className={className ?? 'p-2 rounded-lg text-muted-foreground hover:text-foreground hover:bg-accent transition-colors'}
      title={t('nav.toggleTheme')}
      aria-label={t('nav.toggleTheme')}
    >
      {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
    </button>
  );
}
