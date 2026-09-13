import type { ReactNode } from 'react';
import { ChevronDown, Cloud, Code2, Cog, Globe, RotateCw, Server } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RailhookIcon } from '../../components/icons/RailhookIcon';
import { Band, SectionHeading } from './primitives';
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
 * Third-party marks are the vendors' own full-colour logos, bundled under `/logos/brand` (see
 * SOURCES.md there) because every self-hosted image serves this page and must not reach out for
 * them. Tiles hold only a picture, never text, so no translation can wrap inside one; names sit
 * under each group as captions.
 *
 * The drawing is HTML on a CSS grid rather than an SVG, so that on a phone it reflows instead of
 * shrinking: the flow runs down, and the shared services move beside the API and the worker. The
 * grid areas are the same names in both layouts, transposed (see `.arch-grid` in index.css). To a
 * screen reader the whole figure is one image with a sentence-long description, so every logo
 * inside it is decorative.
 */

const CAPTION = 'arch-cap px-1 py-1 text-center text-[13px] leading-snug';

/** The tile behind a black vendor mark in the dark theme, so the mark stays as the vendor ships it. */
const LIGHT_TILE = 'dark:border-transparent dark:bg-[#F5F7FA]';

function Tile({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span className={cn('grid h-[42px] w-[42px] place-items-center rounded-[10px] border border-rail bg-card shadow-card', className)}>
      {children}
    </span>
  );
}

/**
 * A bundled brand logo. `mono` marks are near-black and the vendor ships a light version of them
 * (GitHub), so they turn light with the theme. A black mark with no light version (Kafka) keeps its
 * colours and sits on a light tile instead — see `LIGHT_TILE`.
 */
function Logo({ name, mono = false }: { name: string; mono?: boolean }) {
  return (
    <img
      src={`/logos/brand/${name}.svg`}
      alt=""
      width={24}
      height={24}
      draggable={false}
      className={cn('h-6 w-6', mono && 'dark:invert')}
    />
  );
}

function Group({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('grid gap-1.5 rounded-xl border border-rail bg-muted p-1.5', className)}>{children}</div>;
}

/**
 * A group or a node with its caption underneath. The line through the row has to meet the picture
 * at its centre, not the centre of picture and caption together, so the caption is mirrored above
 * as an invisible copy (drawn from an attribute, so it adds no text to the page): whatever the
 * caption's length or line count in either language, the picture stays in the middle. On a phone
 * the flow runs down and only the two cells a side line leaves from need that balance.
 *
 * The caption carries the card's own background, so a line running behind it stops at the words
 * instead of striking through them.
 */
function Cell({
  area,
  caption,
  children,
  className,
  strong = false,
  balancedOnPhone = false,
}: {
  area: string;
  caption: string;
  children: ReactNode;
  className?: string;
  strong?: boolean;
  balancedOnPhone?: boolean;
}) {
  const weight = strong ? 'font-semibold text-foreground' : 'font-medium text-muted-foreground';
  return (
    <div style={{ gridArea: area }} className={cn('relative flex flex-col items-center justify-center', className)}>
      <span aria-hidden="true" data-caption={caption} className={cn(CAPTION, weight, 'arch-cap-ghost', balancedOnPhone ? 'block' : 'hidden md:block')} />
      <div className="relative z-[1]">{children}</div>
      <span className={cn(CAPTION, weight, 'relative z-[1] bg-card')}>{caption}</span>
    </div>
  );
}

/**
 * A connector: a chevron in a circle on the line. `cross` is the link to the shared services, which
 * runs across the main flow in both layouts. The motion is not here: one pulse travels the main
 * rail behind every tile and chevron (see `.arch-rail::after`), so nothing moving ever sits on top
 * of a picture.
 */
function Connector({ area, cross = false, retry = false }: { area: string; cross?: boolean; retry?: boolean }) {
  return (
    <div style={{ gridArea: area }} className={cn('arch-link relative', cross && 'arch-link--cross')}>
      <span className="absolute left-1/2 top-1/2 z-[2] grid h-5 w-5 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full border border-rail bg-card text-primary shadow-card">
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
  // Our own and generic nodes carry an icon the size and weight of the logos beside them.
  const glyph = 'h-6 w-6 text-foreground';

  return (
    <figure className="arch-wash m-0 rounded-2xl border border-rail p-3 sm:p-5">
      <div className="rounded-xl border border-rail bg-card p-4 shadow-card sm:p-5">
      <div role="img" aria-label={t('landing.architecture.diagramAria')} className="arch-grid">
        <div aria-hidden="true" className="arch-rail" />
        <div aria-hidden="true" className="arch-svc-rail" />
        <div aria-hidden="true" className="arch-elbow arch-elbow--api" />
        <div aria-hidden="true" className="arch-elbow arch-elbow--wrk" />

        <Cell area="src" caption={t('landing.architecture.sources')} className="arch-end arch-end--start">
          <Group className="grid-cols-2">
            <Tile>
              <Code2 className={glyph} strokeWidth={2} />
            </Tile>
            <Tile>
              <Logo name="stripe" />
            </Tile>
            <Tile>
              <Logo name="github" mono />
            </Tile>
            <Tile>
              <Logo name="shopify" />
            </Tile>
          </Group>
        </Cell>

        <Connector area="c1" />
        <Cell area="api" caption="Railhook API" strong balancedOnPhone className="arch-node-link">
          <span className="grid h-14 w-14 place-items-center rounded-[14px] bg-primary text-primary-foreground shadow-card ring-4 ring-accent">
            <RailhookIcon className="h-7 w-7" />
          </span>
        </Cell>

        <Connector area="c2" />
        <Cell area="kfk" caption="Kafka">
          <Tile className={LIGHT_TILE}>
            <Logo name="apachekafka" />
          </Tile>
        </Cell>

        <Connector area="c3" retry />
        <Cell area="wrk" caption={t('landing.architecture.worker')} balancedOnPhone className="arch-node-link">
          <Tile>
            <Cog className={glyph} strokeWidth={2} />
          </Tile>
        </Cell>

        <Connector area="c4" />
        <Cell area="dst" caption={t('landing.architecture.endpoints')} className="arch-end arch-end--end">
          <Group className="grid-cols-3 md:grid-cols-2">
            <Tile>
              <Globe className={glyph} strokeWidth={2} />
            </Tile>
            <Tile>
              <Server className={glyph} strokeWidth={2} />
            </Tile>
            <Tile className="md:col-span-2 md:justify-self-center">
              <Cloud className={glyph} strokeWidth={2} />
            </Tile>
          </Group>
        </Cell>

        <Connector area="l1" cross />
        <Connector area="l2" cross />
        <div className="arch-svc relative z-[1] flex flex-col items-center bg-card">
          <Group className="grid-cols-1 md:grid-cols-2">
            <Tile>
              <Logo name="postgresql" />
            </Tile>
            <Tile>
              <Logo name="redis" />
            </Tile>
          </Group>
          <span className={cn(CAPTION, 'font-medium text-muted-foreground')}>{t('landing.architecture.shared')}</span>
        </div>
      </div>
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
