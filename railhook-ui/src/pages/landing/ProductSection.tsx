import { useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Band, SectionHeading } from './primitives';
import { cn } from '../../lib/utils';
// Imported rather than served from public/: the build gives each a content hash, so a release
// with new screenshots gets new URLs and no browser keeps last release's image.
import dashboardShot from '../../assets/shots/dashboard.png';
import deliveriesShot from '../../assets/shots/deliveries.png';
import connectionsShot from '../../assets/shots/connections.png';

/**
 * Screenshots of the real admin, one at a time behind tabs — at a third of the page width the
 * admin's own type is smaller than this page's captions. The files are captured from the running
 * product; until one exists the frame shows its caption instead of a broken image.
 */
export default function ProductSection() {
  const { t } = useTranslation();
  const [active, setActive] = useState(0);
  const [failed, setFailed] = useState<Record<string, boolean>>({});
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const shots = [
    { id: 'deliveries', src: deliveriesShot, tab: t('landing.product.deliveriesTab'), body: t('landing.product.deliveriesBody'), alt: t('landing.product.deliveriesAlt') },
    { id: 'dashboard', src: dashboardShot, tab: t('landing.product.dashboardTab'), body: t('landing.product.dashboardBody'), alt: t('landing.product.dashboardAlt') },
    { id: 'connections', src: connectionsShot, tab: t('landing.product.connectionsTab'), body: t('landing.product.connectionsBody'), alt: t('landing.product.connectionsAlt') },
  ];
  const shot = shots[active];

  const onKey = (event: KeyboardEvent<HTMLButtonElement>) => {
    const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
    if (!step) return;
    event.preventDefault();
    const next = (active + step + shots.length) % shots.length;
    setActive(next);
    tabRefs.current[next]?.focus();
  };

  return (
    <Band id="dashboard" muted labelledBy="product-title">
      <SectionHeading id="product-title" title={t('landing.product.title')} lead={t('landing.product.lead')} />

      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div role="tablist" aria-label={t('landing.product.tabsLabel')} className="inline-flex self-start rounded-[10px] border border-input bg-background p-[3px] max-sm:grid max-sm:w-full max-sm:grid-cols-3">
          {shots.map((s, i) => (
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
                'rounded-[7px] px-3 py-1.5 text-[13.5px] font-medium transition-colors max-sm:h-10 max-sm:px-1',
                i === active ? 'bg-foreground text-background' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {s.tab}
            </button>
          ))}
        </div>
        <p className="text-[15px] text-muted-foreground sm:text-right">{shot.body}</p>
      </div>

      <div
        id="product-panel"
        role="tabpanel"
        aria-labelledby={`product-tab-${shot.id}`}
        className="mt-5 overflow-hidden rounded-2xl border border-rail bg-card shadow-elevated"
      >
        {failed[shot.id] ? (
          <div className="flex aspect-[16/10] items-center justify-center">
            <span className="font-mono text-xs text-muted-foreground">{t('landing.product.unavailable')}</span>
          </div>
        ) : (
          /* A 1440px dashboard squeezed to a phone's width is a picture of nothing. Below sm the
             frame shows the working area at roughly twice the size — past the sidebar, from the
             top — and a tap opens the whole screenshot; from sm it is the full image, as before. */
          <a href={shot.src} target="_blank" rel="noopener noreferrer" className="block max-sm:aspect-[5/4] max-sm:overflow-hidden">
            <img
              key={shot.id}
              src={shot.src}
              alt={shot.alt}
              width={1440}
              height={900}
              loading="lazy"
              onError={() => setFailed((f) => ({ ...f, [shot.id]: true }))}
              className="block aspect-[16/10] h-auto w-full object-cover object-top max-sm:-ml-[30%] max-sm:w-[210%] max-sm:max-w-none"
            />
          </a>
        )}
      </div>
      {!failed[shot.id] && <p className="mt-2.5 text-center text-[13px] text-muted-foreground sm:hidden">{t('landing.product.openFull')}</p>}
    </Band>
  );
}
