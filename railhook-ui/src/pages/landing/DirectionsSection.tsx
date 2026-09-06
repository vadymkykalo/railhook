import { Link } from 'react-router-dom';
import { ArrowRight, KeyRound } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { panel, Reveal, Section, SectionHeader } from './primitives';
import { cn } from '../../lib/utils';

/**
 * The one place the two directions are explained, and the direction is drawn
 * the way the product actually runs it.
 *
 * Outgoing fans out: one event a customer announced becomes a delivery to every
 * endpoint their own users registered. Incoming fans in: webhooks from
 * providers the customer connected — Stripe, GitHub, Twilio — arrive at
 * Railhook and are forwarded to a destination the customer nominated. Drawing a
 * provider on the outgoing side would teach the wrong model.
 */

const SOURCE_MARKS = [
  { name: 'Stripe', src: '/logos/stripe.svg' },
  { name: 'GitHub', src: '/logos/github.svg' },
  { name: 'Slack', src: '/logos/slack.svg' },
];

const ENDPOINT_PATHS = ['/hooks/orders', '/webhooks/acme', '/events/in'];

const FAN_Y = [16, 54, 92];

/**
 * Motion here is the mechanism, not decoration: one event reaches Railhook and
 * fans out to every endpoint that subscribed, and several sources fan in and
 * leave as one forward. Each packet is a dash on the same rail the diagram
 * draws, normalised with pathLength so one keyframe pair drives every path.
 * prefers-reduced-motion leaves the rails drawn and the packets gone.
 */
const DIAGRAM_KEYFRAMES = `
  @keyframes hf-dir-first {
    0% { stroke-dashoffset: 0; opacity: 0; }
    4% { opacity: 1; }
    34% { stroke-dashoffset: -102; opacity: 1; }
    37% { stroke-dashoffset: -102; opacity: 0; }
    100% { stroke-dashoffset: -102; opacity: 0; }
  }
  @keyframes hf-dir-second {
    0%, 33% { stroke-dashoffset: 0; opacity: 0; }
    37% { opacity: 1; }
    68% { stroke-dashoffset: -102; opacity: 1; }
    71% { stroke-dashoffset: -102; opacity: 0; }
    100% { stroke-dashoffset: -102; opacity: 0; }
  }
  .hf-dir-first, .hf-dir-second {
    animation-duration: 4.6s;
    animation-timing-function: cubic-bezier(0.45, 0, 0.25, 1);
    animation-iteration-count: infinite;
  }
  .hf-dir-first { animation-name: hf-dir-first; }
  .hf-dir-second { animation-name: hf-dir-second; }
`;

/** A packet riding one of the diagram's rails. */
function Packet({ d, leg }: { d: string; leg: 'first' | 'second' }) {
  return (
    <path
      className={leg === 'first' ? 'hf-dir-first' : 'hf-dir-second'}
      d={d}
      fill="none"
      stroke="hsl(var(--primary))"
      strokeWidth={3.5}
      strokeLinecap="round"
      pathLength={100}
      strokeDasharray="2 100"
    />
  );
}

function OutgoingDiagram() {
  return (
    <svg viewBox="0 0 340 112" className="mt-6 w-full" aria-hidden="true">
      <text x={4} y={58} fontFamily="JetBrains Mono, monospace" fontSize={9} fill="hsl(var(--muted-foreground))">
        your app
      </text>
      <line x1={58} y1={54} x2={112} y2={54} stroke="hsl(var(--rail))" strokeWidth={1} />
      <Packet d="M 58 54 L 112 54" leg="first" />
      <circle cx={58} cy={54} r={3} fill="hsl(var(--primary))" />
      <rect x={112} y={41} width={56} height={26} rx={6} fill="hsl(var(--card))" stroke="hsl(var(--rail))" />
      <text x={140} y={57} textAnchor="middle" fontFamily="JetBrains Mono, monospace" fontSize={8.5} fill="hsl(var(--foreground))">
        railhook
      </text>
      {FAN_Y.map((y) => (
        <g key={y}>
          <path
            d={`M 168 54 C 200 54, 204 ${y}, 232 ${y}`}
            fill="none"
            stroke="hsl(var(--rail))"
            strokeWidth={1}
          />
          <Packet d={`M 168 54 C 200 54, 204 ${y}, 232 ${y}`} leg="second" />
          <circle cx={232} cy={y} r={2.5} fill="none" stroke="hsl(var(--primary))" strokeWidth={1} />
        </g>
      ))}
      {ENDPOINT_PATHS.map((path, i) => (
        <text
          key={path}
          x={242}
          y={FAN_Y[i] + 3.5}
          fontFamily="JetBrains Mono, monospace"
          fontSize={9}
          fill="hsl(var(--muted-foreground))"
        >
          {path}
        </text>
      ))}
    </svg>
  );
}

function IncomingDiagram() {
  return (
    <svg viewBox="0 0 340 112" className="mt-6 w-full" aria-hidden="true">
      {SOURCE_MARKS.map((mark, i) => (
        <g key={mark.name}>
          <image
            href={mark.src}
            x={4}
            y={FAN_Y[i] - 8}
            width={16}
            height={16}
            className="opacity-70 grayscale dark:invert"
          />
          <path
            d={`M 26 ${FAN_Y[i]} C 62 ${FAN_Y[i]}, 84 54, 112 54`}
            fill="none"
            stroke="hsl(var(--rail))"
            strokeWidth={1}
          />
          <Packet d={`M 26 ${FAN_Y[i]} C 62 ${FAN_Y[i]}, 84 54, 112 54`} leg="first" />
        </g>
      ))}
      <rect x={112} y={41} width={56} height={26} rx={6} fill="hsl(var(--card))" stroke="hsl(var(--rail))" />
      <text x={140} y={57} textAnchor="middle" fontFamily="JetBrains Mono, monospace" fontSize={8.5} fill="hsl(var(--foreground))">
        railhook
      </text>
      <line x1={168} y1={54} x2={228} y2={54} stroke="hsl(var(--rail))" strokeWidth={1} />
      <Packet d="M 168 54 L 228 54" leg="second" />
      <circle cx={228} cy={54} r={3} fill="hsl(var(--primary))" />
      <text x={238} y={51} fontFamily="JetBrains Mono, monospace" fontSize={9} fill="hsl(var(--muted-foreground))">
        acme.io
      </text>
      <text x={238} y={64} fontFamily="JetBrains Mono, monospace" fontSize={9} fill="hsl(var(--muted-foreground))">
        /incoming
      </text>
    </svg>
  );
}

function DirectionCard({
  label,
  title,
  body,
  chain,
  ladder,
  cta,
  diagram,
  children,
}: {
  label: string;
  title: string;
  body: string;
  chain: string;
  ladder: string;
  cta: string;
  diagram: React.ReactNode;
  children?: React.ReactNode;
}) {
  return (
    <div className={cn('flex h-full flex-col p-6 sm:p-7', panel(true))}>
      <p className="mono-label">{label}</p>
      <h3 className="mt-3 text-title text-foreground">{title}</h3>
      <p className="mt-3 text-[15px] leading-relaxed text-muted-foreground">{body}</p>
      {diagram}
      {children}
      {/* The incoming card carries a verified-sources list the outgoing one does
          not, so without a spacer the two cards' footers sit at different
          heights and the pair reads as a rendering mistake. */}
      <div className="flex-1" aria-hidden="true" />
      <dl className="mt-6 grid gap-2 border-t border-rail pt-4 font-mono text-[11.5px] text-muted-foreground sm:grid-cols-[auto_minmax(0,1fr)] sm:gap-x-4">
        <dt className="sr-only">chain</dt>
        <dd className="sm:col-span-2">{chain}</dd>
        <dt className="sr-only">ladder</dt>
        <dd className="sm:col-span-2 text-foreground">{ladder}</dd>
      </dl>
      <Link
        to="/register"
        className="mt-5 inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
      >
        {cta} <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
      </Link>
    </div>
  );
}

/**
 * The providers with a verifier of their own, sitting inside the incoming card
 * rather than in a section of their own.
 *
 * They used to lead the page as "what it connects to", one screen below the
 * hero, which spent the second-most-valuable slot on a fact that only matters
 * once the reader has decided incoming is their problem. Here it answers the
 * question at the moment it is asked.
 */
const VERIFIED = [
  { name: 'Stripe', detailKey: 'landing.verified.stripeDetail' },
  { name: 'GitHub', detailKey: 'landing.verified.githubDetail' },
  { name: 'GitLab', detailKey: 'landing.verified.gitlabDetail' },
  { name: 'Slack', detailKey: 'landing.verified.slackDetail' },
  { name: 'Shopify', detailKey: 'landing.verified.shopifyDetail' },
  { name: 'Twilio', detailKey: 'landing.verified.twilioDetail' },
];

function VerifiedSources() {
  const { t } = useTranslation();
  return (
    <div className="mt-6 border-t border-rail pt-4">
      <p className="mono-label">{t('landing.verified.verifiedLabel')}</p>
      <dl className="mt-3 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5">
        {VERIFIED.map((provider) => (
          <div key={provider.name} className="contents">
            <dt className="text-[13.5px] text-foreground">{provider.name}</dt>
            <dd className="truncate font-mono text-[11px] leading-5 text-muted-foreground">
              {t(provider.detailKey)}
            </dd>
          </div>
        ))}
        <dt className="flex items-center gap-1.5 text-[13.5px] text-foreground">
          <KeyRound className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          {t('landing.verified.genericName')}
        </dt>
        <dd className="truncate font-mono text-[11px] leading-5 text-muted-foreground">
          {t('landing.verified.genericDetail')}
        </dd>
      </dl>
      <p className="mt-3 text-[13px] leading-relaxed text-muted-foreground">
        {t('landing.verified.note')}
      </p>
    </div>
  );
}

/**
 * The headers Railhook puts on a delivery, as the outgoing card's counterpart to the incoming
 * card's verified-source list.
 *
 * Without it the outgoing card was a third empty: one card carried a six-row list and the one
 * beside it carried nothing, which reads as a rendering fault rather than a pair. And the
 * mirror is the honest one to draw — what we sign on the way out against what we verify on the
 * way in. Every name here is a header `OutgoingAttemptStore` actually sets.
 */
const SENT_HEADERS = [
  { name: 'X-Signature', detailKey: 'landing.sentHeaders.signature' },
  { name: 'X-Event-Id', detailKey: 'landing.sentHeaders.eventId' },
  { name: 'X-Delivery-Id', detailKey: 'landing.sentHeaders.deliveryId' },
  { name: 'X-Timestamp', detailKey: 'landing.sentHeaders.timestamp' },
  { name: 'X-Sequence-Number', detailKey: 'landing.sentHeaders.sequence' },
  { name: 'Idempotency-Key', detailKey: 'landing.sentHeaders.idempotency' },
];

function SentHeaders() {
  const { t } = useTranslation();
  return (
    <div className="mt-6 border-t border-rail pt-4">
      <p className="mono-label">{t('landing.sentHeaders.label')}</p>
      <dl className="mt-3 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5">
        {SENT_HEADERS.map((header) => (
          <div key={header.name} className="contents">
            <dt className="font-mono text-[11.5px] leading-5 text-foreground">{header.name}</dt>
            <dd className="truncate font-mono text-[11px] leading-5 text-muted-foreground">
              {t(header.detailKey)}
            </dd>
          </div>
        ))}
      </dl>
      <p className="mt-3 text-[13px] leading-relaxed text-muted-foreground">
        {t('landing.sentHeaders.note')}
      </p>
    </div>
  );
}

export default function DirectionsSection() {
  const { t } = useTranslation();
  return (
    <Section id="how-it-works">
      <style>{DIAGRAM_KEYFRAMES}</style>
      <Reveal>
        <SectionHeader
          eyebrow={t('landing.directions.eyebrow')}
          title={t('landing.directions.title')}
          body={t('landing.directions.body')}
        />
      </Reveal>
      <div className="mt-10 grid gap-5 md:grid-cols-2">
        <Reveal className="h-full">
        <DirectionCard
          label={t('landing.directions.outLabel')}
          title={t('landing.directions.outTitle')}
          body={t('landing.directions.outBody')}
          chain={t('landing.directions.outChain')}
          ladder={t('landing.directions.outLadder')}
          cta={t('landing.directions.outCta')}
          diagram={<OutgoingDiagram />}
        >
          <SentHeaders />
        </DirectionCard>
        </Reveal>
        <Reveal className="h-full" delay={90}>
        <DirectionCard
          label={t('landing.directions.inLabel')}
          title={t('landing.directions.inTitle')}
          body={t('landing.directions.inBody')}
          chain={t('landing.directions.inChain')}
          ladder={t('landing.directions.inLadder')}
          cta={t('landing.directions.inCta')}
          diagram={<IncomingDiagram />}
        >
          <VerifiedSources />
        </DirectionCard>
        </Reveal>
      </div>
      <p className="mt-6 max-w-3xl text-sm text-muted-foreground">{t('landing.directions.note')}</p>
    </Section>
  );
}
