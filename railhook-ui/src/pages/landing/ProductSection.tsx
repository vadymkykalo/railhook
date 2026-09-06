import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { panel, Reveal, Section, SectionHeader } from './primitives';
import { cn } from '../../lib/utils';

/**
 * Screenshots of the real admin, framed by rails rather than by a fake OS
 * window. The files are captured from the running product; until one exists the
 * frame degrades to its caption instead of showing a broken image.
 */

function Shot({ src, alt, title, body }: { src: string; alt: string; title: string; body: string }) {
  const { t } = useTranslation();
  const [failed, setFailed] = useState(false);

  return (
    <figure className="flex flex-col">
      <div className={cn('overflow-hidden', panel(true))}>
        <div className="flex items-center justify-between gap-3 border-b border-rail px-3 py-2">
          <span className="truncate font-mono text-[11px] text-muted-foreground">{src.replace('/shots/', '')}</span>
        </div>
        {failed ? (
          <div className="flex aspect-[16/10] items-center justify-center bg-secondary/40">
            <span className="font-mono text-[11px] text-muted-foreground">{t('landing.product.unavailable')}</span>
          </div>
        ) : (
          <img
            src={src}
            alt={alt}
            loading="lazy"
            onError={() => setFailed(true)}
            className="aspect-[16/10] w-full object-cover object-top"
          />
        )}
      </div>
      <figcaption className="mt-3">
        <h3 className="text-[15px] font-semibold text-foreground">{title}</h3>
        <p className="mt-1 text-sm leading-relaxed text-muted-foreground">{body}</p>
      </figcaption>
    </figure>
  );
}

export default function ProductSection() {
  const { t } = useTranslation();
  return (
    <Section id="dashboard">
      <Reveal>
        <SectionHeader
          eyebrow={t('landing.product.eyebrow')}
          title={t('landing.product.title')}
          body={t('landing.product.body')}
        />
      </Reveal>
      {/* One wide frame, then two under it — not a 3-up row and not a tall
          column beside a short one. At a third of the grid the admin's own type
          is smaller than this page's smallest caption, and side-by-side left the
          single frame short against a stack of two, with dead paper under it.
          Stacked, every row closes flush and the wide one still carries the
          product. */}
      <div className="mt-10 space-y-6">
        <Reveal>
        <Shot
          src="/shots/deliveries.png"
          alt={t('landing.product.deliveriesAlt')}
          title={t('landing.product.deliveriesTitle')}
          body={t('landing.product.deliveriesBody')}
        />
        </Reveal>
        <div className="grid gap-6 sm:grid-cols-2">
          <Reveal delay={80}>
            <Shot
              src="/shots/dashboard.png"
              alt={t('landing.product.dashboardAlt')}
              title={t('landing.product.dashboardTitle')}
              body={t('landing.product.dashboardBody')}
            />
          </Reveal>
          <Reveal delay={160}>
            <Shot
              src="/shots/connections.png"
              alt={t('landing.product.connectionsAlt')}
              title={t('landing.product.connectionsTitle')}
              body={t('landing.product.connectionsBody')}
            />
          </Reveal>
        </div>
      </div>
    </Section>
  );
}
