import { useEffect, useRef, useState } from 'react';
import { RAILHOOK_MARK } from '../../components/icons/RailhookIcon';
import { useTranslation } from 'react-i18next';
import AttemptRail, { type RailAttempt } from '../../components/AttemptRail';

/**
 * The hero's subject: one forward walking its ladder.
 *
 * A webhook leaves a source, Railhook creates the forward, the first attempt
 * comes back 500, the ladder waits, and the second attempt lands 200. Nothing
 * here is decoration — every moving thing is a step the product actually takes,
 * and the rail underneath is the same AttemptRail the admin uses.
 *
 * Driven by one requestAnimationFrame loop that moves the packet through a ref
 * and only re-renders React when the phase changes. Under
 * prefers-reduced-motion the loop never starts and the resolved end state is
 * rendered instead.
 */

type Phase =
  | 'announced'
  | 'toHub'
  | 'accepted'
  | 'attempt1'
  | 'failed'
  | 'returning'
  | 'waiting'
  | 'attempt2'
  | 'delivered';

/** Phase boundaries in ms. The cycle is deliberately slow enough to read. */
const TIMELINE: [Phase, number][] = [
  ['announced', 600],
  ['toHub', 1900],
  ['accepted', 2400],
  ['attempt1', 3800],
  ['failed', 4400],
  ['returning', 5200],
  ['waiting', 7200],
  ['attempt2', 8600],
  ['delivered', 11800],
];
const CYCLE = TIMELINE[TIMELINE.length - 1][1];

// Geometry of the panel, in viewBox units.
const RAIL_Y = 92;
const SOURCE_OUT = 106;
const HUB_IN = 234;
const HUB_OUT = 326;
const DEST_IN = 454;

function easeInOut(p: number): number {
  return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
}

function phaseAt(ms: number): { phase: Phase; progress: number } {
  let start = 0;
  for (const [phase, end] of TIMELINE) {
    if (ms < end) return { phase, progress: (ms - start) / (end - start) };
    start = end;
  }
  return { phase: 'delivered', progress: 1 };
}

/** Where the packet sits, or null when it is not in flight. */
function packetAt(phase: Phase, progress: number): number | null {
  const p = easeInOut(Math.min(Math.max(progress, 0), 1));
  switch (phase) {
    case 'toHub':
      return SOURCE_OUT + (HUB_IN - SOURCE_OUT) * p;
    case 'attempt1':
    case 'attempt2':
      return HUB_OUT + (DEST_IN - HUB_OUT) * p;
    case 'returning':
      return DEST_IN + (HUB_OUT - DEST_IN) * p;
    default:
      return null;
  }
}

const ATTEMPT_ONE: RailAttempt = { number: 1, outcome: 'failed', delayMinutes: 0, code: 500 };

function attemptsFor(phase: Phase): RailAttempt[] {
  switch (phase) {
    case 'announced':
    case 'toHub':
    case 'accepted':
      return [{ number: 1, outcome: 'pending', delayMinutes: 0 }];
    case 'attempt1':
      return [{ number: 1, outcome: 'pending', delayMinutes: 0 }];
    case 'failed':
    case 'returning':
    case 'waiting':
      return [ATTEMPT_ONE];
    case 'attempt2':
      return [ATTEMPT_ONE, { number: 2, outcome: 'pending', delayMinutes: 1 }];
    default:
      return [ATTEMPT_ONE, { number: 2, outcome: 'ok', delayMinutes: 1, code: 200 }];
  }
}

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function Node({
  x,
  label,
  sub,
  logo,
  tone,
}: {
  x: number;
  label: string;
  sub: string;
  logo?: string;
  tone: 'idle' | 'live' | 'ok' | 'halt';
}) {
  const stroke =
    tone === 'ok' ? 'hsl(var(--ok))'
      : tone === 'halt' ? 'hsl(var(--halt))'
        : tone === 'live' ? 'hsl(var(--primary))'
          : 'hsl(var(--rail))';
  return (
    <g>
      <rect
        x={x}
        y={RAIL_Y - 28}
        width={88}
        height={56}
        rx={8}
        fill="hsl(var(--card))"
        stroke={stroke}
        strokeWidth={tone === 'idle' ? 1 : 1.5}
        style={{ transition: 'stroke 240ms ease' }}
      />
      {logo ? (
        <image href={logo} x={x + 33} y={RAIL_Y - 11} width={22} height={22} className="grayscale dark:invert" />
      ) : (
        /* The real mark, from its exported geometry. The hand-drawn hook that
           used to sit here was a different shape from the logo in the nav
           directly above it. */
        <g transform={`translate(${x + 34} ${RAIL_Y - 10})`}>
          <rect width={20} height={20} rx={5} fill="hsl(var(--primary))" opacity={0.12} />
          <g
            fill="none"
            stroke="hsl(var(--primary))"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
            transform="translate(10 10) scale(0.68) translate(-10 -10)"
          >
            <path d={RAILHOOK_MARK.hook} />
            <path d={RAILHOOK_MARK.flow} />
            <circle
              cx={RAILHOOK_MARK.origin.cx}
              cy={RAILHOOK_MARK.origin.cy}
              r={RAILHOOK_MARK.origin.r}
              fill="hsl(var(--primary))"
              stroke="none"
            />
          </g>
        </g>
      )}
      <text
        x={x + 44}
        y={RAIL_Y + 46}
        textAnchor="middle"
        fontFamily="JetBrains Mono, monospace"
        fontSize={10}
        fill="hsl(var(--foreground))"
      >
        {label}
      </text>
      <text
        x={x + 44}
        y={RAIL_Y + 60}
        textAnchor="middle"
        fontFamily="JetBrains Mono, monospace"
        fontSize={9}
        fill="hsl(var(--muted-foreground))"
      >
        {sub}
      </text>
    </g>
  );
}

export default function DeliveryFlight() {
  const { t } = useTranslation();
  const packetRef = useRef<SVGGElement | null>(null);
  const figureRef = useRef<HTMLElement | null>(null);
  const [phase, setPhase] = useState<Phase>(prefersReducedMotion() ? 'delivered' : 'announced');
  /* The loop only runs while the panel is on screen. It used to run for the
     whole session — a reader six sections down was still paying for a delivery
     animation nobody could see, on a page whose own pitch is not wasting
     attempts on something that is not going to land. */
  const [onScreen, setOnScreen] = useState(true);

  useEffect(() => {
    const node = figureRef.current;
    if (!node || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(
      (entries) => setOnScreen(entries.some((entry) => entry.isIntersecting)),
      { threshold: 0 },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (prefersReducedMotion() || !onScreen) return;
    let raf = 0;
    let started: number | null = null;
    let current: Phase = 'announced';

    const step = (now: number) => {
      if (started === null) started = now;
      const ms = (now - started) % CYCLE;
      const { phase: next, progress } = phaseAt(ms);
      if (next !== current) {
        current = next;
        setPhase(next);
      }
      const x = packetAt(next, progress);
      const node = packetRef.current;
      if (node) {
        if (x === null) {
          node.setAttribute('opacity', '0');
        } else {
          node.setAttribute('opacity', '1');
          node.setAttribute('transform', `translate(${x.toFixed(2)} ${RAIL_Y})`);
        }
      }
      raf = window.requestAnimationFrame(step);
    };

    raf = window.requestAnimationFrame(step);
    return () => window.cancelAnimationFrame(raf);
  }, [onScreen]);

  const failing = phase === 'failed' || phase === 'returning';
  const status =
    phase === 'delivered' ? { text: t('landing.hero.statusDelivered', { n: 2 }), tone: 'text-ok' }
      : failing ? { text: t('landing.hero.statusFailed', { n: 1 }), tone: 'text-halt' }
        : phase === 'waiting' ? { text: t('landing.hero.statusWaiting', { delay: '1m', n: 2 }), tone: 'text-retry' }
          : { text: t('landing.hero.statusInFlight', { n: phase === 'attempt2' ? 2 : 1 }), tone: 'text-retry' };

  const packetColor = failing ? 'hsl(var(--halt))' : 'hsl(var(--primary))';
  const walked = phase === 'delivered' ? DEST_IN : phase === 'announced' ? SOURCE_OUT : HUB_IN;

  return (
    <figure ref={figureRef} className="overflow-hidden rounded-xl border border-rail bg-card shadow-card">
      <figcaption className="flex items-center justify-between gap-3 border-b border-rail px-4 py-2.5">
        <span className="flex min-w-0 items-baseline gap-2">
          <span className="mono-label">{t('landing.hero.panelLabel')}</span>
          <span className="truncate font-mono text-[11px] text-muted-foreground">fwd_8f2a41c7</span>
        </span>
        <span className={`shrink-0 font-mono text-[11px] font-medium ${status.tone}`}>{status.text}</span>
      </figcaption>

      <svg viewBox="0 0 560 176" className="w-full" role="img" aria-label={t('landing.hero.panelCaption')}>
        {/* The route. Drawn as a hairline the whole way, so the ladder's waits
            read as pauses on a road that is always there. */}
        <line x1={SOURCE_OUT} y1={RAIL_Y} x2={DEST_IN} y2={RAIL_Y} stroke="hsl(var(--rail))" strokeWidth={1} />
        <line
          x1={SOURCE_OUT}
          y1={RAIL_Y}
          x2={walked}
          y2={RAIL_Y}
          stroke="hsl(var(--primary))"
          strokeWidth={1}
          opacity={0.5}
          style={{ transition: 'all 400ms ease' }}
        />
        {/* The attempted hop, dashed until it has landed. */}
        <line
          x1={HUB_OUT}
          y1={RAIL_Y}
          x2={DEST_IN}
          y2={RAIL_Y}
          stroke={phase === 'delivered' ? 'hsl(var(--ok))' : 'hsl(var(--rail))'}
          strokeWidth={1}
          strokeDasharray={phase === 'delivered' ? undefined : '3 4'}
        />

        <Node
          x={16}
          label="stripe"
          sub="charge.succeeded"
          logo="/logos/stripe.svg"
          tone={phase === 'announced' || phase === 'toHub' ? 'live' : 'idle'}
        />
        <Node
          x={236}
          label="railhook"
          sub={phase === 'waiting' ? 'ladder · 1m' : 'forward'}
          tone={phase === 'accepted' || phase === 'waiting' ? 'live' : 'idle'}
        />
        <Node
          x={456}
          label="slack"
          sub="hooks.slack.com"
          logo="/logos/slack.svg"
          tone={phase === 'delivered' ? 'ok' : failing ? 'halt' : 'idle'}
        />

        {/* The response code the attempt resolved to. */}
        {(failing || phase === 'delivered') && (
          <text
            x={500}
            y={RAIL_Y - 38}
            textAnchor="middle"
            fontFamily="JetBrains Mono, monospace"
            fontSize={11}
            fill={phase === 'delivered' ? 'hsl(var(--ok))' : 'hsl(var(--halt))'}
            className="animate-fade-in"
          >
            {phase === 'delivered' ? '200' : '500'}
          </text>
        )}

        {/* The waiting rung: the ladder holding the delivery back. */}
        {phase === 'waiting' && (
          <g className="animate-fade-in">
            <circle cx={280} cy={RAIL_Y} r={20} fill="none" stroke="hsl(var(--retry))" strokeWidth={1} opacity={0.5} />
            <text
              x={280}
              y={RAIL_Y - 38}
              textAnchor="middle"
              fontFamily="JetBrains Mono, monospace"
              fontSize={10}
              fill="hsl(var(--retry))"
            >
              wait 1m
            </text>
          </g>
        )}

        {/* The packet itself. Moved by the rAF loop, hidden between hops. */}
        <g ref={packetRef} opacity={0} transform={`translate(${SOURCE_OUT} ${RAIL_Y})`}>
          <line x1={-22} y1={0} x2={-4} y2={0} stroke={packetColor} strokeWidth={1.5} opacity={0.35} strokeLinecap="round" />
          <circle r={4.5} fill={packetColor} />
          <circle r={9} fill="none" stroke={packetColor} strokeWidth={1} opacity={0.3} />
        </g>
      </svg>

      <div className="border-t border-rail px-4 py-3.5">
        <AttemptRail
          attempts={attemptsFor(phase)}
          maxAttempts={5}
          size="full"
          ariaLabel={t('landing.hero.railAria')}
        />
        <p className="mt-1.5 font-mono text-[11px] text-muted-foreground">{t('landing.hero.ladderCaption')}</p>
      </div>
    </figure>
  );
}
