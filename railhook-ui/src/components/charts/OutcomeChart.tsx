import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import {
  AREA_FILL_OPACITY, CHART_MARGIN, SERIES, cursorProps, formatCompact, gridProps, xAxisProps, yAxisProps,
} from './chartTheme';
import ChartTooltip, { type ChartTooltipItem } from './ChartTooltip';
import type { LegendItem } from './ChartLegend';

export interface OutcomePoint {
  timestamp: string;
  success: number;
  failed: number;
}

interface OutcomeChartProps {
  data: OutcomePoint[];
  labels: { success: string; failed: string };
  /** Axis ticks: short. Tooltip heading: full. */
  formatTick: (timestamp: string) => string;
  formatStamp: (timestamp: string) => string;
}

/**
 * Delivery outcome over time — the one chart that answers "is my traffic
 * healthy right now", and the only chart in the product whose series are
 * allowed to wear status hues, because the series *are* the statuses: the
 * lower band is what was delivered, the upper band is what failed. Total
 * height stays readable as volume, so a drop in traffic and a rise in
 * failures never look alike.
 */
export default function OutcomeChart({ data, labels, formatTick, formatStamp }: OutcomeChartProps) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <AreaChart data={data} margin={CHART_MARGIN}>
        <CartesianGrid {...gridProps} />
        <XAxis dataKey="timestamp" {...xAxisProps} tickFormatter={formatTick} />
        <YAxis {...yAxisProps} tickFormatter={formatCompact} />
        <Tooltip
          cursor={cursorProps}
          content={(props) => (
            <ChartTooltip
              active={props.active}
              label={props.label}
              payload={props.payload as unknown as ChartTooltipItem[]}
              labelFormatter={(v) => formatStamp(String(v))}
              valueFormatter={(v) => formatCompact(v)}
            />
          )}
        />
        <Area
          type="monotone"
          dataKey="success"
          name={labels.success}
          stackId="outcome"
          stroke={SERIES.ok}
          strokeWidth={2}
          fill={SERIES.ok}
          fillOpacity={AREA_FILL_OPACITY}
          isAnimationActive={false}
        />
        <Area
          type="monotone"
          dataKey="failed"
          name={labels.failed}
          stackId="outcome"
          stroke={SERIES.halt}
          strokeWidth={2}
          fill={SERIES.halt}
          fillOpacity={AREA_FILL_OPACITY}
          isAnimationActive={false}
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}

export function outcomeLegend(labels: { success: string; failed: string }): LegendItem[] {
  return [
    { label: labels.success, color: SERIES.ok, shape: 'rect' },
    { label: labels.failed, color: SERIES.halt, shape: 'rect' },
  ];
}
