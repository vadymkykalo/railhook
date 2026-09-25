import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';

/** Not flags: Windows renders emoji flags as letter pairs, and a language isn't a country. */

const LANGUAGES = [
  { code: 'en', label: 'EN', name: 'English' },
  { code: 'uk', label: 'UK', name: 'Українська' },
] as const;

interface LanguageSwitcherProps {
  variant?: 'icon' | 'full';
  className?: string;
}

export default function LanguageSwitcher({ variant = 'icon', className }: LanguageSwitcherProps) {
  const { t, i18n } = useTranslation();
  const activeIndex = Math.max(
    LANGUAGES.findIndex((l) => i18n.language?.startsWith(l.code)),
    0
  );

  if (variant === 'full') {
    return (
      <div role="group" aria-label={t('settings.language')} className={cn('flex flex-col gap-1', className)}>
        {LANGUAGES.map((lang, i) => (
          <button
            key={lang.code}
            type="button"
            onClick={() => i18n.changeLanguage(lang.code)}
            aria-current={i === activeIndex ? 'true' : undefined}
            className={cn(
              'flex items-center gap-2 px-3 py-2 text-left text-[13px] transition-colors',
              i === activeIndex
                ? 'bg-secondary font-medium text-foreground'
                : 'text-muted-foreground hover:bg-secondary/60 hover:text-foreground'
            )}
          >
            <span className="font-mono text-[11px] tracking-wider">{lang.label}</span>
            <span>{lang.name}</span>
          </button>
        ))}
      </div>
    );
  }

  return (
    <div
      role="group"
      aria-label={t('settings.language')}
      className={cn(
        // A 40px tap target per half on a phone.
        'inline-flex h-8 items-stretch border border-input max-sm:h-10',
        className
      )}
    >
      {LANGUAGES.map((lang) => {
        const active = LANGUAGES[activeIndex].code === lang.code;
        return (
          <button
            key={lang.code}
            type="button"
            lang={lang.code}
            onClick={() => i18n.changeLanguage(lang.code)}
            aria-pressed={active}
            title={lang.name}
            className={cn(
              'px-2.5 font-mono text-[12px] font-medium leading-none transition-colors max-sm:px-3.5',
              active ? 'bg-foreground text-background' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            {lang.label}
          </button>
        );
      })}
    </div>
  );
}
