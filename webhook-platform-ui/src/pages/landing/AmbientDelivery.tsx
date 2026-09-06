/**
 * The ambient layer behind the hero band.
 *
 * It draws the thing the product does — webhooks arriving from several sources,
 * passing through Railhook, leaving for several destinations, and one of them
 * looping back into the ladder — and nothing else. No particles, no
 * constellations, no drifting stars: if it would look at home on another
 * product's page it does not belong on this one.
 *
 * Everything is a hairline in rail or low-opacity brand teal, and a packet
 * takes twenty to thirty seconds to cross, so it never competes with the type
 * in front of it. Motion is CSS only, so the global prefers-reduced-motion rule
 * stops it dead with every packet resting at its destination.
 */

interface Route {
  d: string;
  /** Dash period: the distance between packets on this route. */
  period: number;
  duration: number;
  delay: number;
}

const ROUTES: Route[] = [
  { d: 'M -60 108 C 300 108, 430 296, 700 300', period: 900, duration: 26, delay: 0 },
  { d: 'M -60 236 C 330 236, 470 298, 700 302', period: 900, duration: 22, delay: -9 },
  { d: 'M -60 430 C 300 430, 450 322, 700 306', period: 900, duration: 30, delay: -17 },
  { d: 'M 740 300 C 1020 300, 1100 150, 1500 150', period: 900, duration: 24, delay: -4 },
  { d: 'M 740 306 C 1040 306, 1120 404, 1500 404', period: 900, duration: 28, delay: -13 },
  { d: 'M 740 312 C 1000 312, 1140 520, 1500 520', period: 900, duration: 32, delay: -21 },
];

/** The one route that fails and re-enters the ladder. */
const RETRY_LOOP = 'M 1180 404 C 1268 470, 1010 512, 928 448 C 884 414, 892 356, 948 330';

/** The ladder itself, drawn once at the hub. */
const HUB_TICKS = [0, 1, 5, 15, 60, 360, 1440];

function tickY(minutes: number): number {
  const p = minutes <= 0 ? 0 : Math.log1p(minutes) / Math.log1p(1440);
  return 244 + p * 116;
}

export default function AmbientDelivery({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={`pointer-events-none absolute inset-0 overflow-hidden ${className ?? ''}`}
      style={{ maskImage: 'linear-gradient(to bottom, transparent, #000 18%, #000 62%, transparent)', WebkitMaskImage: 'linear-gradient(to bottom, transparent, #000 18%, #000 62%, transparent)' }}
    >
      <style>{`
        @keyframes hf-ambient-travel { from { stroke-dashoffset: 900; } to { stroke-dashoffset: 0; } }
        @keyframes hf-ambient-loop { from { stroke-dashoffset: 420; } to { stroke-dashoffset: 0; } }
        .hf-ambient-packet { animation-name: hf-ambient-travel; animation-timing-function: linear; animation-iteration-count: infinite; }
        .hf-ambient-retry { animation: hf-ambient-loop 19s linear infinite; }
      `}</style>
      <svg viewBox="0 0 1440 640" preserveAspectRatio="xMidYMid slice" className="h-full w-full">
        {ROUTES.map((route) => (
          <g key={route.d}>
            <path d={route.d} fill="none" stroke="hsl(var(--rail))" strokeWidth={1} opacity={0.55} />
            <path
              className="hf-ambient-packet"
              d={route.d}
              fill="none"
              stroke="hsl(var(--primary))"
              strokeWidth={3}
              strokeLinecap="round"
              opacity={0.16}
              strokeDasharray={`3 ${route.period}`}
              style={{ animationDuration: `${route.duration}s`, animationDelay: `${route.delay}s` }}
            />
          </g>
        ))}

        {/* One route comes back: an attempt that failed and re-entered the ladder. */}
        <path d={RETRY_LOOP} fill="none" stroke="hsl(var(--rail))" strokeWidth={1} strokeDasharray="4 6" opacity={0.5} />
        <path
          className="hf-ambient-retry"
          d={RETRY_LOOP}
          fill="none"
          stroke="hsl(var(--primary))"
          strokeWidth={3}
          strokeLinecap="round"
          opacity={0.14}
          strokeDasharray="3 420"
        />

        {/* The hub, drawn as the ladder it runs. */}
        <line x1={720} y1={236} x2={720} y2={368} stroke="hsl(var(--rail))" strokeWidth={1} />
        {HUB_TICKS.map((m) => (
          <line
            key={m}
            x1={713}
            y1={tickY(m)}
            x2={727}
            y2={tickY(m)}
            stroke="hsl(var(--rail))"
            strokeWidth={1}
          />
        ))}
      </svg>
    </div>
  );
}
