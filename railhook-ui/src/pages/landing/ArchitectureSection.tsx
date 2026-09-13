import type { CSSProperties, ReactNode } from 'react';
import { ChevronDown, Code2, Cog, Globe, RotateCw, Server } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RailhookIcon } from '../../components/icons/RailhookIcon';
import { Band, LogoMark, SectionHeading } from './primitives';
import { cn } from '../../lib/utils';

/**
 * What Railhook is built on, for the reader who has to trust it with events: a short why on the
 * left, and on the right the two directions as one picture — senders, the API, Kafka, the worker,
 * where it all goes, and the PostgreSQL and Redis both services lean on.
 *
 * Every box is true of the shipped compose file and the code. The API writes the Event and its
 * Outbox row in one transaction and only then answers; Kafka carries the work to the worker; the
 * worker attempts, and retries on the ladder. Redis holds rate limits, ordering and the circuit
 * breaker, not events. Nothing here promises availability numbers, because nothing measures them.
 *
 * The drawing is HTML on a CSS grid rather than an SVG, so that on a phone it reflows instead of
 * shrinking: the flow runs down, and the shared services move beside the boxes they serve. The
 * grid areas are the same names in both layouts, transposed (see `.arch-grid` in index.css). To a
 * screen reader the whole figure is one image with a sentence-long description.
 */

const LOGO = { stripe: '#635BFF', postgresql: '#4169E1', redis: '#FF4438' } as const;

function Tile({
  area,
  icon,
  label,
  accent = false,
  link = false,
}: {
  area: string;
  icon: ReactNode;
  label: string;
  accent?: boolean;
  /** Draws the start of the line to the shared service beside or below this tile. */
  link?: boolean;
}) {
  return (
    <div style={{ gridArea: area }} className={cn('relative flex items-center justify-center', link && 'arch-node-link')}>
      <div
        className={cn(
          'relative z-[1] flex w-full max-w-[6rem] flex-col items-center gap-1.5 rounded-[11px] border px-1 py-2.5 text-center shadow-card',
          accent ? 'border-primary bg-primary text-primary-foreground' : 'border-rail bg-card text-foreground',
        )}
      >
        {icon}
        <span className={cn('font-mono text-[11px] leading-tight', accent ? 'text-primary-foreground' : 'text-muted-foreground')}>
          {label}
        </span>
      </div>
    </div>
  );
}

function Group({ area, items }: { area: string; items: { icon: ReactNode; label: string }[] }) {
  return (
    <div style={{ gridArea: area }} className="relative z-[1] flex items-center">
      <div className="grid w-full gap-1 rounded-xl border border-dashed border-input bg-card p-1.5">
        {items.map((item) => (
          <div key={item.label} className="flex items-center gap-1.5 rounded-lg border border-rail bg-card px-1.5 py-1.5 shadow-card">
            {item.icon}
            <span className="font-mono text-[11px] leading-tight text-muted-foreground">{item.label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * A connector: a chevron in a circle, and a dot travelling through. `cross` is the link to a
 * shared service, which runs across the main flow in both layouts.
 */
function Connector({ area, delay, cross = false, retry = false }: { area: string; delay: string; cross?: boolean; retry?: boolean }) {
  return (
    <div style={{ gridArea: area }} className={cn('arch-link relative', cross && 'arch-link--cross')}>
      <span className="arch-dot" style={{ animationDelay: delay } as CSSProperties} />
      <span className="absolute left-1/2 top-1/2 z-[2] grid h-[18px] w-[18px] -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full border border-rail bg-card text-primary shadow-card">
        {retry ? (
          <RotateCw className="h-2.5 w-2.5" strokeWidth={2.5} />
        ) : (
          <ChevronDown className={cn('h-3 w-3', cross ? '-rotate-90 md:rotate-0' : 'md:-rotate-90')} strokeWidth={2.5} />
        )}
      </span>
    </div>
  );
}

function ArchitectureDiagram() {
  const { t } = useTranslation();
  const icon = 'h-[18px] w-[18px]';
  const small = 'h-3.5 w-3.5';

  return (
    <figure className="m-0 rounded-2xl border border-rail bg-gradient-to-b from-accent to-background p-5 shadow-card sm:p-6">
      <div role="img" aria-label={t('landing.architecture.diagramAria')} className="arch-grid">
        <div aria-hidden="true" className="arch-rail" />
        <div aria-hidden="true" className="arch-svc rounded-xl border border-dashed border-input" />

        <Group
          area="src"
          items={[
            { icon: <Code2 className={cn(small, 'shrink-0 text-foreground')} />, label: t('landing.architecture.yourApp') },
            { icon: <LogoMark name="stripe" color={LOGO.stripe} className={small} />, label: 'Stripe' },
            { icon: <LogoMark name="github" className={cn(small, 'text-foreground')} />, label: 'GitHub' },
          ]}
        />
        <Connector area="c1" delay="0s" />
        <Tile area="api" accent link icon={<RailhookIcon className={icon} />} label="Railhook API" />
        <Connector area="c2" delay="-0.9s" />
        <Tile area="kfk" icon={<LogoMark name="apachekafka" className={icon} />} label="Kafka" />
        <Connector area="c3" delay="-1.8s" retry />
        <Tile area="wrk" link icon={<Cog className={icon} />} label={t('landing.architecture.worker')} />
        <Connector area="c4" delay="-2.7s" />
        <Group
          area="dst"
          items={[
            { icon: <Globe className={cn(small, 'shrink-0 text-foreground')} />, label: t('landing.architecture.customers') },
            { icon: <Server className={cn(small, 'shrink-0 text-foreground')} />, label: t('landing.architecture.yourServices') },
          ]}
        />

        <Connector area="l1" delay="-0.4s" cross />
        <Connector area="l2" delay="-2.1s" cross />
        <Tile area="pg" icon={<LogoMark name="postgresql" color={LOGO.postgresql} className={icon} />} label="PostgreSQL" />
        <div
          style={{ gridArea: 'lbl' }}
          className="relative z-[1] flex items-center justify-center text-center font-mono text-[10.5px] uppercase leading-tight tracking-[0.08em] text-muted-foreground"
        >
          {t('landing.architecture.shared')}
        </div>
        <Tile area="rds" icon={<LogoMark name="redis" color={LOGO.redis} className={icon} />} label="Redis" />
      </div>
    </figure>
  );
}

export default function ArchitectureSection() {
  const { t } = useTranslation();
  const facts = [t('landing.architecture.factStored'), t('landing.architecture.factRetries'), t('landing.architecture.factDeploy')];

  return (
    <Band id="architecture" labelledBy="architecture-title">
      <div className="grid items-center gap-10 lg:grid-cols-[minmax(0,5fr)_minmax(0,8fr)] lg:gap-12">
        <div>
          <SectionHeading id="architecture-title" title={t('landing.architecture.title')} className="mb-0" />
          <div className="mt-4 grid gap-2 text-[1.05rem] text-muted-foreground">
            <p>{t('landing.architecture.stored')}</p>
            <p>{t('landing.architecture.survives')}</p>
            <p>{t('landing.architecture.runsOn')}</p>
          </div>
          <ul aria-label={t('landing.architecture.factsLabel')} className="mt-6 flex flex-wrap gap-2">
            {facts.map((fact) => (
              <li key={fact} className="rounded-md border border-rail bg-muted px-2 py-1 font-mono text-xs text-muted-foreground">
                {fact}
              </li>
            ))}
          </ul>
        </div>
        <ArchitectureDiagram />
      </div>
    </Band>
  );
}
