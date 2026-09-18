import { useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Lock } from 'lucide-react';
import { Band, SectionHeading } from './primitives';
import { cn } from '../../lib/utils';
import { useIsDarkTheme } from '../../hooks/useIsDarkTheme';
import { getTheme } from '../../lib/theme';

/** The screens, in the order a reader meets them: what went out, why one retried, what gave up, what came in, what a customer sees, and the totals. */
const SCREENS = [
  { id: 'deliveries', host: 'railhook.io' },
  { id: 'attempts', host: 'railhook.io' },
  { id: 'failed', host: 'railhook.io' },
  { id: 'incoming', host: 'railhook.io' },
  { id: 'portal', host: 'acme-shop.com' },
  { id: 'analytics', host: 'railhook.io' },
] as const;

/** The captured size, in CSS pixels; the files are twice that for high-density screens. */
const SHOT_WIDTH = 1440;
const SHOT_HEIGHT = 900;

/**
 * Screenshots of the real product, one at a time behind tabs, straight after the hero so a
 * visitor sees what they would be running before reading about it. The files are captured from a
 * running stack seeded with demo data, in both themes. The dark one is a <picture> source whose
 * media query is the theme: the system preference when the reader never chose one — which is also
 * what the prerendered page carries, so the browser picks the right file before any script runs —
 * and a plain yes or no when they did. The browser downloads only the file it picks; two <img>s
 * with one hidden by CSS would download both, since Chrome fetches a lazy image even when it is
 * display:none.
 */
export default function ProductSection() {
  const { t } = useTranslation();
  const [active, setActive] = useState(0);
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const screen = SCREENS[active];
  const isDark = useIsDarkTheme();
  const darkMedia = getTheme() === 'system' ? '(prefers-color-scheme: dark)' : isDark ? 'all' : 'not all';

  const select = (index: number) => {
    setActive(index);
    tabRefs.current[index]?.focus();
    tabRefs.current[index]?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  };

  const onKey = (event: KeyboardEvent<HTMLButtonElement>) => {
    const last = SCREENS.length - 1;
    const next =
      event.key === 'ArrowRight' ? (active === last ? 0 : active + 1)
      : event.key === 'ArrowLeft' ? (active === 0 ? last : active - 1)
      : event.key === 'Home' ? 0
      : event.key === 'End' ? last
      : null;
    if (next === null) return;
    event.preventDefault();
    select(next);
  };

  return (
    <Band id="see-it" labelledBy="product-title">
      <SectionHeading id="product-title" title={t('landing.product.title')} lead={t('landing.product.lead')} />

      {/* On a phone the pills scroll sideways inside their own strip, so the page itself never
          does; the negative margin lets the strip run to the screen's edges. */}
      <div
        role="tablist"
        aria-label={t('landing.product.tabsLabel')}
        className="-mx-5 flex gap-2 overflow-x-auto px-5 pb-1 [scrollbar-width:none] sm:mx-0 sm:flex-wrap sm:px-0 [&::-webkit-scrollbar]:hidden"
      >
        {SCREENS.map((s, i) => (
          <button
            key={s.id}
            ref={(node) => {
              tabRefs.current[i] = node;
            }}
            type="button"
            role="tab"
            id={`product-tab-${s.id}`}
            aria-selected={i === active}
            aria-controls="product-panel"
            tabIndex={i === active ? 0 : -1}
            onClick={() => setActive(i)}
            onKeyDown={onKey}
            className={cn(
              'h-10 shrink-0 whitespace-nowrap rounded-full border px-4 text-[14px] font-medium transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
              i === active
                ? 'border-primary bg-primary text-primary-foreground'
                : 'border-rail bg-card text-muted-foreground hover:border-primary/40 hover:text-foreground',
            )}
          >
            {t(`landing.product.${s.id}.tab`)}
          </button>
        ))}
      </div>

      <div id="product-panel" role="tabpanel" aria-labelledby={`product-tab-${screen.id}`} className="mt-5">
        <p className="mb-4 min-h-[1.5em] text-[15px] text-muted-foreground">{t(`landing.product.${screen.id}.caption`)}</p>

        <figure className="overflow-hidden rounded-xl border border-rail bg-card shadow-elevated-lg">
          <div aria-hidden="true" className="flex h-9 items-center gap-3 border-b border-rail bg-muted px-3.5">
            <span className="flex gap-1.5">
              <span className="h-2.5 w-2.5 rounded-full bg-rail" />
              <span className="h-2.5 w-2.5 rounded-full bg-rail" />
              <span className="h-2.5 w-2.5 rounded-full bg-rail" />
            </span>
            <span className="mx-auto inline-flex h-6 min-w-0 max-w-[60%] items-center gap-1.5 rounded-md bg-background px-3 font-mono text-[11.5px] text-muted-foreground max-sm:hidden sm:w-72 sm:justify-center">
              <Lock className="h-3 w-3 shrink-0" />
              <span className="truncate">{screen.host}</span>
            </span>
            <span className="w-[46px] max-sm:hidden" />
          </div>
          <div className="aspect-[16/10] bg-background">
            <picture key={screen.id}>
              <source srcSet={`/screens/${screen.id}-dark.webp`} media={darkMedia} />
              <img
                src={`/screens/${screen.id}-light.webp`}
                alt={t(`landing.product.${screen.id}.alt`)}
                width={SHOT_WIDTH}
                height={SHOT_HEIGHT}
                loading="lazy"
                decoding="async"
                className="h-full w-full object-cover object-top"
              />
            </picture>
          </div>
        </figure>
      </div>
    </Band>
  );
}
