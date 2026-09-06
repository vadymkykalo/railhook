import { useMemo } from 'react';
import { cn } from '../lib/utils';

/**
 * The attempt rail.
 *
 * A retry ladder is not a progress bar: the waits between attempts grow
 * exponentially (1m → 5m → 15m → 1h → 6h → 24h), so attempt 6 sits a day away
 * from attempt 1 while attempts 1–3 crowd into the first quarter hour. Placing
 * the ticks evenly would draw a schedule the product does not actually run, so
 * they are placed on a log scale of their delay and the crowding is the point.
 *
 * Used inline in a delivery row, and full-width on a delivery's detail view.
 */

export type AttemptOutcome = 'ok' | 'failed' | 'pending' | 'scheduled';

export interface RailAttempt {
  /** 1-based attempt number. */
  number: number;
  outcome: AttemptOutcome;
  /** Minutes waited before this attempt was made. Attempt 1 is 0. */
  delayMinutes: number;
  /** HTTP status the attempt resolved to, when it resolved to one. */
  code?: number;
}

interface AttemptRailProps {
  attempts: RailAttempt[];
  /** Rungs the ladder has in total, so an unfinished ladder shows what is left. */
  maxAttempts?: number;
  size?: 'inline' | 'full';
  /** Screen-reader description; callers own the wording so it stays translated. */
  ariaLabel: string;
  className?: string;
}

const OUTCOME_COLOR: Record<AttemptOutcome, string> = {
  ok: 'hsl(var(--ok))',
  failed: 'hsl(var(--halt))',
  pending: 'hsl(var(--retry))',
  scheduled: 'hsl(var(--rail))',
};

/** Log placement, with attempt 1 pinned to the left edge. */
function positionOf(delayMinutes: number, span: number): number {
  if (delayMinutes <= 0) return 0;
  return Math.log1p(delayMinutes) / Math.log1p(span);
}

function formatDelay(minutes: number): string {
  if (minutes <= 0) return '0';
  if (minutes < 60) return `${Math.round(minutes)}m`;
  if (minutes < 1440) return `${Math.round(minutes / 60)}h`;
  return `${Math.round(minutes / 1440)}d`;
}

export default function AttemptRail({
  attempts,
  maxAttempts,
  size = 'inline',
  ariaLabel,
  className,
}: AttemptRailProps) {
  const full = size === 'full';
  const width = full ? 560 : 104;
  const height = full ? 44 : 16;
  const baselineY = full ? 20 : 8;
  const padX = full ? 10 : 5;
  const usable = width - padX * 2;

  const { ticks, span } = useMemo(() => {
    const rungs = Math.max(maxAttempts ?? attempts.length, attempts.length, 2);
    // The ladder this product ships: doubling-ish waits out to a day.
    const ladder = [0, 1, 5, 15, 60, 360, 1440, 2880];
    const all: RailAttempt[] = [...attempts];
    for (let n = attempts.length + 1; n <= rungs; n++) {
      all.push({
        number: n,
        outcome: 'scheduled',
        delayMinutes: ladder[Math.min(n - 1, ladder.length - 1)],
      });
    }
    const maxDelay = Math.max(...all.map((a) => a.delayMinutes), 1);
    return { ticks: all, span: maxDelay };
  }, [attempts, maxAttempts]);

  // The rail is drawn as far as the ladder has actually been walked.
  const lastRealIndex = attempts.length - 1;
  const progressX =
    lastRealIndex >= 0
      ? padX + positionOf(attempts[lastRealIndex].delayMinutes, span) * usable
      : padX;

  return (
    <svg
      role="img"
      aria-label={ariaLabel}
      viewBox={`0 0 ${width} ${height}`}
      width={full ? '100%' : width}
      height={full ? undefined : height}
      className={cn(full && 'w-full', className)}
      style={full ? { maxWidth: width } : undefined}
    >
      {/* The unwalked remainder of the ladder */}
      <line
        x1={padX}
        y1={baselineY}
        x2={width - padX}
        y2={baselineY}
        stroke="hsl(var(--rail))"
        strokeWidth={1}
        strokeDasharray="2 3"
      />
      {/* The part that has been walked */}
      <line
        x1={padX}
        y1={baselineY}
        x2={progressX}
        y2={baselineY}
        stroke="hsl(var(--rail))"
        strokeWidth={1}
      />

      {ticks.map((a, i) => {
        const x = padX + positionOf(a.delayMinutes, span) * usable;
        const color = OUTCOME_COLOR[a.outcome];
        const resolved = a.outcome === 'ok';
        const r = full ? (resolved ? 5 : 3.5) : resolved ? 3.5 : 2.5;
        return (
          <g key={a.number} className="rail-tick-in" style={{ animationDelay: `${i * 60}ms` }}>
            {resolved && (
              <circle cx={x} cy={baselineY} r={r + 3} fill="none" stroke={color} strokeWidth={1} opacity={0.4} />
            )}
            <circle
              cx={x}
              cy={baselineY}
              r={r}
              fill={a.outcome === 'scheduled' ? 'hsl(var(--background))' : color}
              stroke={color}
              strokeWidth={a.outcome === 'scheduled' ? 1 : 0}
            />
            {full && (
              <>
                <text
                  x={x}
                  y={baselineY - 10}
                  textAnchor="middle"
                  fontSize={9}
                  fontFamily="JetBrains Mono, monospace"
                  fill={a.code ? color : 'hsl(var(--muted-foreground))'}
                >
                  {a.code ?? ''}
                </text>
                <text
                  x={x}
                  y={baselineY + 16}
                  textAnchor="middle"
                  fontSize={9}
                  fontFamily="JetBrains Mono, monospace"
                  fill="hsl(var(--muted-foreground))"
                >
                  {formatDelay(a.delayMinutes)}
                </text>
              </>
            )}
          </g>
        );
      })}
    </svg>
  );
}
