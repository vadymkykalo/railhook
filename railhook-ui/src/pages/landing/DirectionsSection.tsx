import type { ReactNode } from 'react';
import { ArrowDownLeft, ArrowUpRight, type LucideIcon } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Band, SectionHeading } from './primitives';
import { ReceiveScene, SendScene } from './DeliveryScenes';

/**
 * The two directions, one card each, in words a buyer uses — webhooks you send to your customers,
 * and webhooks other services send to you — and under the words, each one happening: deliveries
 * arriving (one after a retry), requests checked on the way in (one refused). No code; how either
 * works is the docs' job.
 */
function Direction({
  icon: Icon,
  tag,
  title,
  body,
  children,
}: {
  icon: LucideIcon;
  tag: string;
  title: string;
  body: string;
  children: ReactNode;
}) {
  return (
    <article className="flex flex-col overflow-hidden rounded-2xl border border-rail bg-card">
      <div className="grid content-start gap-3 p-6 pb-5 sm:p-7 sm:pb-5">
        <p className="inline-flex items-center gap-2 font-mono text-xs uppercase tracking-[0.08em] text-primary">
          <Icon className="h-3.5 w-3.5" aria-hidden="true" />
          {tag}
        </p>
        <h3 className="text-[1.35rem] font-semibold tracking-[-0.01em] text-foreground">{title}</h3>
        <p className="text-muted-foreground">{body}</p>
      </div>
      <div className="mt-auto border-t border-rail bg-muted/60 px-3 pb-4 pt-4 sm:px-5">{children}</div>
    </article>
  );
}

export default function DirectionsSection() {
  const { t } = useTranslation();
  return (
    <Band id="product" muted labelledBy="directions-title">
      <SectionHeading id="directions-title" title={t('landing.directions.title')} />
      <div className="grid gap-5 md:grid-cols-2">
        <Direction
          icon={ArrowUpRight}
          tag={t('landing.directions.outTag')}
          title={t('landing.directions.outTitle')}
          body={t('landing.directions.outBody')}
        >
          <SendScene />
        </Direction>
        <Direction
          icon={ArrowDownLeft}
          tag={t('landing.directions.inTag')}
          title={t('landing.directions.inTitle')}
          body={t('landing.directions.inBody')}
        >
          <ReceiveScene />
        </Direction>
      </div>
    </Band>
  );
}
