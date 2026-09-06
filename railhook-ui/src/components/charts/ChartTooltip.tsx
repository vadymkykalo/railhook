import { CHROME } from './chartTheme';

export interface ChartTooltipItem {
  name?: string;
  value?: number | string | (number | string)[];
  color?: string;
  dataKey?: string | number;
}

interface ChartTooltipProps {
  active?: boolean;
  label?: string | number;
  payload?: ChartTooltipItem[];
  /** Renders the crosshair's x position — a date, a bucket, a category. */
  labelFormatter?: (label: string | number) => string;
  valueFormatter?: (value: number, dataKey: string) => string;
}

/**
 * One tooltip for every chart in the product.
 *
 * Two rules it exists to hold: it lists *every* series at the hovered position,
 * so the pointer never has to land on a particular line to get a value; and the
 * value leads while the series name follows, because by the time someone is
 * hovering they already know which series they want. Series names arrive from
 * the API (event types, endpoint URLs) and are rendered as text nodes only.
 */
export default function ChartTooltip({
  active, label, payload, labelFormatter, valueFormatter,
}: ChartTooltipProps) {
  if (!active || !payload || payload.length === 0) return null;

  return (
    <div className="pointer-events-none min-w-[9rem] rounded-lg border border-rail bg-popover px-3 py-2 shadow-elevated">
      {label !== undefined && (
        <p className="mb-1.5 font-mono text-[11px] uppercase tracking-[0.08em] text-muted-foreground">
          {labelFormatter ? labelFormatter(label) : String(label)}
        </p>
      )}
      <ul className="space-y-1">
        {payload.map((item, i) => {
          const raw = Array.isArray(item.value) ? item.value[item.value.length - 1] : item.value;
          const numeric = typeof raw === 'number' ? raw : Number(raw);
          const key = String(item.dataKey ?? item.name ?? i);
          return (
            <li key={key} className="flex items-baseline justify-between gap-3">
              <span className="flex min-w-0 items-center gap-1.5">
                <span
                  aria-hidden
                  className="h-0.5 w-3 flex-shrink-0 rounded-full"
                  style={{ backgroundColor: item.color ?? CHROME.muted }}
                />
                <span className="truncate text-xs text-muted-foreground">{item.name}</span>
              </span>
              <span className="font-mono text-[13px] font-medium tabular-nums text-foreground">
                {valueFormatter && Number.isFinite(numeric)
                  ? valueFormatter(numeric, key)
                  : String(raw ?? '')}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
