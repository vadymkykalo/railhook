import { Bar, BarChart, Cell, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { CHROME, MONO_STACK, SERIES, formatCompact, ordinalStep, yAxisProps } from './chartTheme';
import ChartTooltip, { type ChartTooltipItem } from './ChartTooltip';

export interface RankDatum {
  key: string;
  label: string;
  value: number;
}

interface BarRankChartProps {
  data: RankDatum[];
  seriesLabel: string;
  formatValue?: (value: number) => string;
  ordinal?: boolean;
  categoryWidth?: number;
  maxLabelChars?: number;
}

export const BAR_ROW_HEIGHT = 30;

/** Sized to the rows so the card never grows a nested scrollbar. */
export function barChartHeight(rowCount: number): number {
  return Math.max(rowCount, 1) * BAR_ROW_HEIGHT + 16;
}

/** One hue for nominal categories: bar length already carries the value. */
export default function BarRankChart({
  data, seriesLabel, formatValue = formatCompact, ordinal = false,
  categoryWidth = 132, maxLabelChars = 22,
}: BarRankChartProps) {
  const rows = data.map((d) => ({ ...d, valueLabel: formatValue(d.value) }));
  const truncate = (value: string) =>
    value.length > maxLabelChars ? `${value.slice(0, maxLabelChars - 1)}…` : value;

  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart layout="vertical" data={rows} margin={{ top: 4, right: 64, bottom: 4, left: 4 }}>
        <XAxis type="number" hide />
        <YAxis
          type="category"
          dataKey="label"
          {...yAxisProps}
          width={categoryWidth}
          tickFormatter={truncate}
          tick={{ fill: CHROME.muted, fontSize: 11, fontFamily: MONO_STACK }}
        />
        <Tooltip
          cursor={{ fill: CHROME.rail, fillOpacity: 0.35 }}
          content={(props) => (
            <ChartTooltip
              active={props.active}
              label={props.label}
              payload={props.payload as unknown as ChartTooltipItem[]}
              valueFormatter={(v) => formatValue(v)}
            />
          )}
        />
        <Bar
          dataKey="value"
          name={seriesLabel}
          fill={SERIES.brand}
          radius={[0, 4, 4, 0]}
          barSize={14}
          isAnimationActive={false}
        >
          {rows.map((row, i) => (
            <Cell key={row.key} fillOpacity={ordinal ? ordinalStep(i, rows.length) : 1} />
          ))}
          <LabelList
            dataKey="valueLabel"
            position="right"
            offset={8}
            fill={CHROME.muted}
            fontSize={11}
            fontFamily={MONO_STACK}
          />
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
