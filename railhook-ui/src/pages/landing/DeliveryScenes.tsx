import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { RAILHOOK_MARK } from '../../components/icons/RailhookIcon';
import { cn } from '../../lib/utils';
import { useSceneClock } from './useSceneClock';

/**
 * The two directions as they happen, one looping scene each, drawn over the same rail vocabulary as
 * the hero map: an event leaves your app and reaches three customers, one of which is down and is
 * retried; requests from real services are checked on the way in and a forged one is refused.
 *
 * Everything is derived from one clock (`time`, milliseconds into the loop), so the packets, the
 * endpoint statuses and the log underneath cannot disagree. `time === null` is the finished
 * picture — what a crawler, the prerender and a reader who asked for less motion see.
 *
 * Status colours appear only on statuses, as everywhere else on the page.
 */

const C = {
  line: 'hsl(var(--input))',
  card: 'hsl(var(--card))',
  edge: 'hsl(var(--input))',
  ink: 'hsl(var(--foreground))',
  slate: 'hsl(var(--muted-foreground))',
  accent: 'hsl(var(--primary))',
  onAccent: 'hsl(var(--primary-foreground))',
  ok: 'hsl(var(--ok))',
  okSoft: 'hsl(var(--ok-soft))',
  retry: 'hsl(var(--retry))',
  halt: 'hsl(var(--halt))',
  haltSoft: 'hsl(var(--halt-soft))',
};

type Pt = readonly [number, number];
type Curve = readonly [Pt, Pt, Pt, Pt];

/** A horizontal S-curve from one node's edge to another's, drawn and travelled alike. */
function between(from: Pt, to: Pt): Curve {
  const dx = (to[0] - from[0]) * 0.5;
  return [from, [from[0] + dx, from[1]], [to[0] - dx, to[1]], to];
}

function pathOf(c: Curve): string {
  return `M${c[0][0]} ${c[0][1]} C${c[1][0]} ${c[1][1]} ${c[2][0]} ${c[2][1]} ${c[3][0]} ${c[3][1]}`;
}

function pointOn(c: Curve, t: number): Pt {
  const u = 1 - t;
  const a = u * u * u;
  const b = 3 * u * u * t;
  const d = 3 * u * t * t;
  const e = t * t * t;
  return [
    a * c[0][0] + b * c[1][0] + d * c[2][0] + e * c[3][0],
    a * c[0][1] + b * c[1][1] + d * c[2][1] + e * c[3][1],
  ];
}

/** 0 before `from`, 1 after `to`, eased in between. */
function progress(time: number, from: number, to: number): number {
  if (time <= from) return 0;
  if (time >= to) return 1;
  const p = (time - from) / (to - from);
  return p < 0.5 ? 2 * p * p : 1 - (-2 * p + 2) ** 2 / 2;
}

const during = (time: number | null, from: number, to: number): time is number =>
  time !== null && time >= from && time < to;

/** True once the loop has passed `at` — and always in the finished picture. */
const since = (time: number | null, at: number) => time === null || time >= at;

function Packet({ curve, time, from, to, color = C.accent }: { curve: Curve; time: number | null; from: number; to: number; color?: string }) {
  if (!during(time, from, to)) return null;
  const [x, y] = pointOn(curve, progress(time, from, to));
  return (
    <g>
      <circle cx={x} cy={y} r="10" fill={color} opacity="0.16" />
      <circle cx={x} cy={y} r="4.5" fill={color} />
    </g>
  );
}

/** A ring that grows and fades where something just happened. */
function Ping({ cx, cy, time, at, color, span = 800 }: { cx: number; cy: number; time: number | null; at: number; color: string; span?: number }) {
  if (!during(time, at, at + span)) return null;
  const p = (time - at) / span;
  return <circle cx={cx} cy={cy} r={6 + p * 16} fill="none" stroke={color} strokeWidth="2" opacity={0.7 * (1 - p)} />;
}

function Hub({ cx, cy, ring }: { cx: number; cy: number; ring?: string }) {
  const size = 54;
  return (
    <g>
      {ring && <rect x={cx - size / 2 - 5} y={cy - size / 2 - 5} width={size + 10} height={size + 10} rx="18" fill="none" stroke={ring} strokeOpacity="0.45" strokeWidth="2" />}
      <rect x={cx - size / 2} y={cy - size / 2} width={size} height={size} rx="15" fill={C.accent} />
      <g transform={`translate(${cx - 16} ${cy - 16}) scale(1.6)`} fill="none" stroke={C.onAccent} strokeWidth="2.25" strokeLinecap="round" strokeLinejoin="round">
        <path d={RAILHOOK_MARK.hook} />
        <path d={RAILHOOK_MARK.flow} />
        <circle {...RAILHOOK_MARK.origin} fill={C.onAccent} stroke="none" />
      </g>
    </g>
  );
}

function Node({ x, cy, w, h = 48, dashed, children }: { x: number; cy: number; w: number; h?: number; dashed?: boolean; children: ReactNode }) {
  return (
    <g>
      <rect x={x} y={cy - h / 2} width={w} height={h} rx="11" fill={C.card} stroke={C.edge} strokeDasharray={dashed ? '4 3' : undefined} />
      {children}
    </g>
  );
}

type Kind = 'ok' | 'retry' | 'halt';

const CHIP: Record<Kind, string> = {
  ok: 'bg-ok-soft text-ok',
  retry: 'bg-retry-soft text-retry',
  halt: 'bg-halt-soft text-halt',
};

function Chip({ kind, children }: { kind: Kind; children: ReactNode }) {
  return (
    <span className={cn('inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-[10.5px] font-semibold', CHIP[kind])}>
      <i className="h-1.5 w-1.5 rounded-full bg-current" />
      {children}
    </span>
  );
}

/** One line of the log. Hidden rows keep their space, so the card never changes height. */
function LogRow({ shown, children }: { shown: boolean; children: ReactNode }) {
  return (
    <li
      className={cn(
        'grid grid-cols-[minmax(0,1fr)_auto_auto] items-center gap-2.5 rounded-lg border border-rail bg-card px-3 py-[7px] transition-[opacity,transform] duration-300 motion-reduce:transition-none',
        shown ? 'translate-y-0 opacity-100' : 'translate-y-1 opacity-0',
      )}
    >
      {children}
    </li>
  );
}

function Scene({ sceneRef, motion, summary, drawing, log }: {
  sceneRef: React.Ref<HTMLDivElement>;
  motion: string;
  summary: string;
  drawing: ReactNode;
  log: ReactNode;
}) {
  return (
    <div ref={sceneRef} data-motion={motion}>
      <p className="sr-only">{summary}</p>
      <div aria-hidden="true" className="grid gap-3">
        {drawing}
        <ol className="grid gap-1.5 font-mono text-[11.5px]">{log}</ol>
      </div>
    </div>
  );
}

// ── Send ────────────────────────────────────────────────────────────────

const SEND_LOOP = 8200;
const SEND = { emit: 250, atHub: 1050, arrive: 1900, retry: 4700, retried: 5500 };
const SEND_APP = { x: 6, w: 132 };
const SEND_HUB: Pt = [214, 118];
const EP = { x: 314, w: 140 };
const ENDPOINTS: readonly { host: string; cy: number; down?: boolean }[] = [
  { host: 'acme.com', cy: 34 },
  { host: 'shop.io', cy: 118 },
  { host: 'crm.dev', cy: 202, down: true },
];

const SEND_IN = between([SEND_APP.x + SEND_APP.w, 118], [SEND_HUB[0] - 27, 118]);
const SEND_OUT = ENDPOINTS.map((e) => between([SEND_HUB[0] + 27, 118], [EP.x, e.cy]));

export function SendScene() {
  const { t } = useTranslation();
  const { ref, motion, time } = useSceneClock<HTMLDivElement>(SEND_LOOP);
  const s = (key: string, options?: Record<string, unknown>) => t(`landing.directions.scene.${key}`, options);

  const status = (down: boolean): { kind: Kind | 'wait'; code: string; label: string } => {
    if (!since(time, SEND.arrive)) return { kind: 'wait', code: '···', label: '' };
    if (down && !since(time, SEND.retried)) return { kind: 'halt', code: '503', label: s('retryIn') };
    return { kind: 'ok', code: '200', label: s('delivered') };
  };
  const tone = { wait: C.slate, ok: C.ok, halt: C.halt, retry: C.retry };

  const drawing = (
    <svg viewBox="0 0 460 246" className="block h-auto w-full">
      {/* Nudged down to share the receive scene's centre line, so the two logs start level. */}
      <g transform="translate(0 6)">
      <path d={pathOf(SEND_IN)} fill="none" stroke={C.line} strokeWidth="2" />
      {/* Under the boxes, so a ring shows round the edge and never over a name. */}
      <Ping cx={EP.x} cy={ENDPOINTS[0].cy} time={time} at={SEND.arrive} color={C.ok} />
      <Ping cx={EP.x} cy={ENDPOINTS[1].cy} time={time} at={SEND.arrive} color={C.ok} />
      <Ping cx={EP.x} cy={ENDPOINTS[2].cy} time={time} at={SEND.arrive} color={C.halt} />
      <Ping cx={EP.x} cy={ENDPOINTS[2].cy} time={time} at={SEND.retried} color={C.ok} />
      {SEND_OUT.map((curve, i) => (
        <path
          key={ENDPOINTS[i].host}
          d={pathOf(curve)}
          fill="none"
          stroke={ENDPOINTS[i].down && during(time, SEND.arrive, SEND.retry) ? C.retry : C.line}
          strokeDasharray={ENDPOINTS[i].down && during(time, SEND.arrive, SEND.retry) ? '3 5' : undefined}
          strokeWidth="2"
        />
      ))}

      <Node x={SEND_APP.x} cy={118} w={SEND_APP.w} h={54}>
        <text x={SEND_APP.x + 14} y={113} fill={C.ink} fontSize="12.5" fontWeight="600" className="font-sans">{t('landing.directions.yourApp')}</text>
        <text x={SEND_APP.x + 14} y={131} fill={C.accent} fontSize="11" className="font-mono">order.paid</text>
      </Node>

      <Hub cx={SEND_HUB[0]} cy={SEND_HUB[1]} ring={during(time, SEND.atHub - 80, SEND.atHub + 500) ? C.accent : undefined} />

      {ENDPOINTS.map((e) => {
        const st = status(Boolean(e.down));
        return (
          <Node key={e.host} x={EP.x} cy={e.cy} w={EP.w}>
            <text x={EP.x + 14} y={e.cy - 4} fill={C.ink} fontSize="12.5" fontWeight="600" className="font-mono">{e.host}</text>
            <text x={EP.x + 14} y={e.cy + 13} fontSize="10.5" className="font-mono">
              <tspan fill={tone[st.kind]} fontWeight="700">{st.code}</tspan>
              {st.label && <tspan fill={C.slate} dx="6">{st.label}</tspan>}
            </text>
            <circle cx={EP.x + EP.w - 16} cy={e.cy - 8} r="4" fill={tone[st.kind]} />
            {e.down && during(time, SEND.arrive, SEND.retry) && (
              <circle
                cx={EP.x + EP.w - 16}
                cy={e.cy - 8}
                r="8.5"
                fill="none"
                stroke={C.retry}
                strokeWidth="2"
                strokeDasharray={`${53.4 * progress(time, SEND.arrive, SEND.retry)} 53.4`}
                transform={`rotate(-90 ${EP.x + EP.w - 16} ${e.cy - 8})`}
              />
            )}
          </Node>
        );
      })}

      <Packet curve={SEND_IN} time={time} from={SEND.emit} to={SEND.atHub} />
      {SEND_OUT.map((curve, i) => (
        <Packet key={ENDPOINTS[i].host} curve={curve} time={time} from={SEND.atHub} to={SEND.arrive} />
      ))}
      <Packet curve={SEND_OUT[2]} time={time} from={SEND.retry} to={SEND.retried} color={C.retry} />
      </g>
    </svg>
  );

  const log = (
    <>
      {ENDPOINTS.slice(0, 2).map((e) => (
        <LogRow key={e.host} shown={since(time, SEND.arrive)}>
          <span className="truncate text-foreground"><span className="hidden text-muted-foreground sm:inline">order.paid → </span>{e.host}</span>
          <Chip kind="ok">{s('delivered')}</Chip>
          <span className="text-muted-foreground">{s('attempt', { n: 1 })}</span>
        </LogRow>
      ))}
      <LogRow shown={since(time, SEND.arrive)}>
        <span className="truncate text-foreground"><span className="hidden text-muted-foreground sm:inline">order.paid → </span>crm.dev</span>
        <Chip kind="retry">{s('retrying')}</Chip>
        <span className="text-muted-foreground">{s('attempt', { n: 1 })}</span>
      </LogRow>
      <LogRow shown={since(time, SEND.retried)}>
        <span className="truncate text-foreground"><span className="hidden text-muted-foreground sm:inline">order.paid → </span>crm.dev</span>
        <Chip kind="ok">{s('delivered')}</Chip>
        <span className="text-muted-foreground">{s('attempt', { n: 2 })}</span>
      </LogRow>
    </>
  );

  return <Scene sceneRef={ref} motion={motion} summary={s('sendSummary')} drawing={drawing} log={log} />;
}

// ── Receive ─────────────────────────────────────────────────────────────

const RECEIVE_LOOP = 8400;
const TRAVEL = 800;
const FORWARD = 650;
const SRC = { x: 6, w: 132 };
const RECEIVE_HUB: Pt = [234, 124];
const RECEIVE_APP = { x: 322, w: 132, cy: 124 };

const SOURCES = [
  { id: 'stripe', name: 'Stripe', logo: '/logos/brand/stripe.svg', event: 'invoice.paid', cy: 30, at: 1000 },
  { id: 'github', name: 'GitHub', logo: '/logos/brand/github.svg', event: 'push', cy: 92, at: 2700 },
  { id: 'forged', name: null, logo: null, event: 'invoice.paid', cy: 154, at: 4400, forged: true },
  { id: 'shopify', name: 'Shopify', logo: '/logos/brand/shopify.svg', event: 'orders/create', cy: 216, at: 6100 },
] as const;

const RECEIVE_IN = SOURCES.map((src) => between([SRC.x + SRC.w, src.cy], [RECEIVE_HUB[0] - 27, RECEIVE_HUB[1]]));
const RECEIVE_OUT = between([RECEIVE_HUB[0] + 27, RECEIVE_HUB[1]], [RECEIVE_APP.x, RECEIVE_APP.cy]);
const DROP: Curve = [[RECEIVE_HUB[0], RECEIVE_HUB[1] + 27], [RECEIVE_HUB[0], RECEIVE_HUB[1] + 50], [RECEIVE_HUB[0] + 4, RECEIVE_HUB[1] + 70], [RECEIVE_HUB[0] + 10, RECEIVE_HUB[1] + 96]];

export function ReceiveScene() {
  const { t } = useTranslation();
  const { ref, motion, time } = useSceneClock<HTMLDivElement>(RECEIVE_LOOP);
  const s = (key: string, options?: Record<string, unknown>) => t(`landing.directions.scene.${key}`, options);

  // What the badge over the hub says: the most recent arrival within its display window.
  const current = [...SOURCES].reverse().find((src) => during(time, src.at, src.at + 1400));
  const verdict: 'ok' | 'halt' | null = time === null ? 'ok' : current ? ('forged' in current ? 'halt' : 'ok') : null;
  const appFlash = time === null || SOURCES.some((src) => !('forged' in src) && during(time, src.at + FORWARD, src.at + FORWARD + 900));

  const drawing = (
    <svg viewBox="0 0 460 246" className="block h-auto w-full">
      {RECEIVE_IN.map((curve, i) => (
        <path key={SOURCES[i].id} d={pathOf(curve)} fill="none" stroke={C.line} strokeWidth="2" strokeDasharray={'forged' in SOURCES[i] ? '3 5' : undefined} />
      ))}
      <path d={pathOf(RECEIVE_OUT)} fill="none" stroke={C.line} strokeWidth="2" />
      {SOURCES.filter((src) => !('forged' in src)).map((src) => (
        <Ping key={src.id} cx={RECEIVE_APP.x} cy={RECEIVE_APP.cy} time={time} at={src.at + FORWARD} color={C.ok} />
      ))}

      {SOURCES.map((src) => (
        <Node key={src.id} x={SRC.x} cy={src.cy} w={SRC.w} h={46} dashed={'forged' in src}>
          {src.logo ? (
            <image href={src.logo} x={SRC.x + 12} y={src.cy - 10} width="20" height="20" className={src.id === 'github' ? 'dark:invert' : undefined} />
          ) : (
            <g>
              <circle cx={SRC.x + 22} cy={src.cy} r="10" fill="none" stroke={C.slate} strokeDasharray="2 2.5" />
              <text x={SRC.x + 22} y={src.cy + 4} textAnchor="middle" fill={C.slate} fontSize="11" fontWeight="700" className="font-sans">?</text>
            </g>
          )}
          <text x={SRC.x + 42} y={src.cy - 2} fill={src.name ? C.ink : C.slate} fontSize="12.5" fontWeight="600" className="font-sans">
            {src.name ?? s('unknownSender')}
          </text>
          <text x={SRC.x + 42} y={src.cy + 13} fill={C.slate} fontSize="10" className="font-mono">{src.event}</text>
        </Node>
      ))}

      <Hub
        cx={RECEIVE_HUB[0]}
        cy={RECEIVE_HUB[1]}
        ring={time !== null && current && during(time, current.at - 60, current.at + 500) ? ('forged' in current ? C.halt : C.accent) : undefined}
      />

      {verdict && (
        <g>
          <rect x={RECEIVE_HUB[0] - 74} y={RECEIVE_HUB[1] - 70} width="148" height="26" rx="13" fill={verdict === 'ok' ? C.okSoft : C.haltSoft} />
          {verdict === 'ok' ? (
            <path d={`M${RECEIVE_HUB[0] - 58} ${RECEIVE_HUB[1] - 57} l3.5 3.5 l6.5 -7`} fill="none" stroke={C.ok} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          ) : (
            <path d={`M${RECEIVE_HUB[0] - 58} ${RECEIVE_HUB[1] - 61} l7 7 m0 -7 l-7 7`} fill="none" stroke={C.halt} strokeWidth="2" strokeLinecap="round" />
          )}
          <text x={RECEIVE_HUB[0] - 44} y={RECEIVE_HUB[1] - 53} fill={verdict === 'ok' ? C.ok : C.halt} fontSize="11" fontWeight="600" className="font-mono">
            {verdict === 'ok' ? s('signatureOk') : s('invalidSignature')}
          </text>
        </g>
      )}

      <Node x={RECEIVE_APP.x} cy={RECEIVE_APP.cy} w={RECEIVE_APP.w} h={54}>
        <text x={RECEIVE_APP.x + 14} y={RECEIVE_APP.cy - 5} fill={C.ink} fontSize="12.5" fontWeight="600" className="font-sans">{t('landing.directions.yourApp')}</text>
        <text x={RECEIVE_APP.x + 14} y={RECEIVE_APP.cy + 13} fontSize="10.5" fontWeight={appFlash ? 700 : 400} fill={appFlash ? C.ok : C.slate} className="font-mono">
          {appFlash ? '200 OK' : 'POST /hooks'}
        </text>
      </Node>

      {SOURCES.map((src, i) => (
        <Packet key={src.id} curve={RECEIVE_IN[i]} time={time} from={src.at - TRAVEL} to={src.at} />
      ))}
      {SOURCES.map((src) =>
        'forged' in src ? (
          <Packet key={src.id} curve={DROP} time={time} from={src.at} to={src.at + 700} color={C.halt} />
        ) : (
          <Packet key={src.id} curve={RECEIVE_OUT} time={time} from={src.at} to={src.at + FORWARD} />
        ),
      )}
    </svg>
  );

  const log = SOURCES.map((src) => (
    <LogRow key={src.id} shown={since(time, src.at)}>
      <span className="truncate text-foreground">
        <span className="text-muted-foreground">{src.name ?? s('unknownSender')} · </span>
        {src.event}
      </span>
      {'forged' in src ? <Chip kind="halt">{s('rejected')}</Chip> : <Chip kind="ok">{s('verified')}</Chip>}
      <span className="text-muted-foreground">{'forged' in src ? '401' : '→ 200'}</span>
    </LogRow>
  ));

  return <Scene sceneRef={ref} motion={motion} summary={s('receiveSummary')} drawing={drawing} log={log} />;
}
