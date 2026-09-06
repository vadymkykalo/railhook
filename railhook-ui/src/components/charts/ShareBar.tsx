import { cn } from '../../lib/utils';
import { SERIES, type SeriesToken, formatCompact, formatRate, share } from './chartTheme';

export interface ShareSegment {
  key: string;
  label: string;
  value: number;
  /** Only ever a status token here: every segment of this bar *is* an outcome. */
  token: Extract<SeriesToken, 'ok' | 'retry' | 'halt' | 'idle'>;
}

/**
 * Part-to-whole for one total, as a single stacked bar rather than four
 * unrelated progress bars each against its own maximum — which is what the
 * page used to draw, and made 88 abandoned deliveries look like the same
 * quantity as 182,710 delivered ones.
 *
 * Segments are separated by a 2px gap in the surface colour, never by a stroke
 * around each fill, and a non-zero segment never rounds away to nothing.
 */
export default function ShareBar({
  segments, total, className,
}: {
  segments: ShareSegment[];
  total: number;
  className?: string;
}) {
  const present = segments.filter((s) => s.value > 0);

  return (
    <div className={cn('space-y-3', className)}>
      <div className="flex h-2.5 w-full gap-0.5 overflow-hidden rounded-full bg-muted" role="presentation">
        {present.map((segment) => (
          <span
            key={segment.key}
            className="h-full rounded-full first:rounded-l-full last:rounded-r-full"
            style={{
              backgroundColor: SERIES[segment.token],
              width: `${Math.max(share(segment.value, total), 0.75)}%`,
              minWidth: 3,
            }}
          />
        ))}
      </div>
      <ul className="grid gap-x-4 gap-y-1.5 sm:grid-cols-2">
        {segments.map((segment) => (
          <li key={segment.key} className="flex items-baseline justify-between gap-3 text-sm">
            <span className="flex min-w-0 items-center gap-2">
              <span
                aria-hidden
                className="h-2 w-2 flex-shrink-0 rounded-sm"
                style={{ backgroundColor: SERIES[segment.token] }}
              />
              <span className="truncate text-muted-foreground">{segment.label}</span>
            </span>
            <span className="flex-shrink-0 font-mono text-[13px] tabular-nums">
              {formatCompact(segment.value)}
              <span className="ml-1.5 text-muted-foreground">{formatRate(share(segment.value, total))}%</span>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
