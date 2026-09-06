import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import {
  AREA_FILL_OPACITY, CHART_MARGIN, SERIES, cursorProps, gridProps, xAxisProps, yAxisProps,
} from './chartTheme';
import ChartTooltip, { type ChartTooltipItem } from './ChartTooltip';

interface TrendChartProps {
  data: Record<string, unknown>[];
  dataKey: string;
  /** Names the single series for the tooltip. No legend: the title says it. */
  seriesLabel: string;
  formatTick: (timestamp: string) => string;
  formatStamp: (timestamp: string) => string;
  formatValue: (value: number) => string;
}

/**
 * One measure over time — latency, throughput, anything that is not an
 * outcome. A single series, so it takes the brand hue rather than a status
 * one: nothing about a latency curve means delivered or abandoned.
 */
export default function TrendChart({
  data, dataKey, seriesLabel, formatTick, formatStamp, formatValue,
}: TrendChartProps) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <AreaChart data={data} margin={CHART_MARGIN}>
        <CartesianGrid {...gridProps} />
        <XAxis dataKey="timestamp" {...xAxisProps} tickFormatter={formatTick} />
        <YAxis {...yAxisProps} tickFormatter={(v) => formatValue(Number(v))} width={56} />
        <Tooltip
          cursor={cursorProps}
          content={(props) => (
            <ChartTooltip
              active={props.active}
              label={props.label}
              payload={props.payload as unknown as ChartTooltipItem[]}
              labelFormatter={(v) => formatStamp(String(v))}
              valueFormatter={(v) => formatValue(v)}
            />
          )}
        />
        <Area
          type="monotone"
          dataKey={dataKey}
          name={seriesLabel}
          stroke={SERIES.brand}
          strokeWidth={2}
          fill={SERIES.brand}
          fillOpacity={AREA_FILL_OPACITY}
          isAnimationActive={false}
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}
