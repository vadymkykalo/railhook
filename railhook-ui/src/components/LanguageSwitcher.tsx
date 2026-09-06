import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';

/**
 * A segmented control rather than a flag.
 *
 * Emoji flags were the previous design and they are unreliable: Windows renders
 * them as bare letter pairs, so the control looked different on every other
 * machine. A language is also not a country. Two mono labels in a rail-ruled
 * track show both choices at once and say which one is active, which a
 * single-button toggle never did.
 */

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
              'flex items-center gap-2 rounded-md px-3 py-2 text-left text-[13px] transition-colors',
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
        'relative inline-flex h-7 items-center rounded-md border border-rail bg-card p-0.5',
        className
      )}
    >
      {/* The indicator slides rather than snapping, so the change reads as one control. */}
      <span
        aria-hidden
        className="absolute inset-y-0.5 w-[calc(50%-2px)] rounded-[5px] bg-secondary transition-transform duration-200 ease-out"
        style={{ transform: `translateX(${activeIndex * 100}%)` }}
      />
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
              'relative z-10 px-2 font-mono text-[11px] font-medium tracking-wider transition-colors',
              active ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            {lang.label}
          </button>
        );
      })}
    </div>
  );
}
