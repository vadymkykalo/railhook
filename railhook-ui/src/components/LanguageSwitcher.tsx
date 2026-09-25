import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';

/**
 * A segmented control rather than a flag.
 *
 * Emoji flags were the previous design and they are unreliable: Windows renders
 * them as bare letter pairs, so the control looked different on every other
 * machine. A language is also not a country. Two mono labels in a hairline
 * box show both choices at once, the active one in ink, which a single-button
 * toggle never did.
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
        // Each half is a 40px tap target on a phone; the track is 32px from sm.
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
