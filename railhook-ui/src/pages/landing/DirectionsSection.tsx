import { ArrowDownLeft, ArrowUpRight, type LucideIcon } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Band, SectionHeading } from './primitives';
import { cn } from '../../lib/utils';

/**
 * The two directions, one card each, in words a buyer uses: webhooks you send to your customers,
 * and webhooks other services send to you. No code — how either works is the docs' job.
 */
function Direction({
  icon: Icon,
  tag,
  title,
  body,
  flow,
}: {
  icon: LucideIcon;
  tag: string;
  title: string;
  body: string;
  flow: string[];
}) {
  return (
    <article className="grid content-start gap-3.5 rounded-2xl border border-rail bg-card p-6 sm:p-7">
      <p className="inline-flex items-center gap-2 font-mono text-xs uppercase tracking-[0.08em] text-primary">
        <Icon className="h-3.5 w-3.5" aria-hidden="true" />
        {tag}
      </p>
      <h3 className="text-[1.35rem] font-semibold tracking-[-0.01em] text-foreground">{title}</h3>
      <p className="text-muted-foreground">{body}</p>
      <div className="mt-1.5 flex flex-wrap items-center gap-2.5 text-[13.5px] font-medium">
        {flow.map((node, i) => (
          <span key={node} className="contents">
            {i > 0 && (
              <span aria-hidden="true" className="font-mono text-muted-foreground">
                →
              </span>
            )}
            <span
              className={cn(
                'rounded-lg border px-2.5 py-1',
                node === 'Railhook' ? 'border-primary bg-primary text-primary-foreground' : 'border-input bg-background text-foreground',
              )}
            >
              {node}
            </span>
          </span>
        ))}
      </div>
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
          flow={[t('landing.directions.yourApp'), 'Railhook', t('landing.directions.customer')]}
        />
        <Direction
          icon={ArrowDownLeft}
          tag={t('landing.directions.inTag')}
          title={t('landing.directions.inTitle')}
          body={t('landing.directions.inBody')}
          flow={['Stripe', 'Railhook', t('landing.directions.yourApp')]}
        />
      </div>
    </Band>
  );
}
