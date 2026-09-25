import i18n from '../../i18n';

/** ok/retry/halt/idle are reserved for status; every other series takes the one brand hue. */

export const SERIES = {
  ok: 'hsl(var(--ok))',
  retry: 'hsl(var(--retry))',
  halt: 'hsl(var(--halt))',
  idle: 'hsl(var(--idle))',
  brand: 'hsl(var(--primary))',
} as const;

export type SeriesToken = keyof typeof SERIES;

export const CHROME = {
  rail: 'hsl(var(--rail))',
  surface: 'hsl(var(--card))',
  ink: 'hsl(var(--foreground))',
  muted: 'hsl(var(--muted-foreground))',
} as const;

export const MONO_STACK = 'JetBrains Mono, ui-monospace, SFMono-Regular, monospace';

/** Opacity steps, not hex, so the ramp re-anchors in dark mode; each step clears OKLCH ΔL >= 0.06. */
export const ORDINAL_STEPS = [0.49, 0.61, 0.74, 0.87, 1] as const;

export function ordinalStep(index: number, count: number): number {
  if (count <= 1) return 1;
  const slot = Math.round((index / (count - 1)) * (ORDINAL_STEPS.length - 1));
  return ORDINAL_STEPS[Math.min(Math.max(slot, 0), ORDINAL_STEPS.length - 1)];
}

export const AREA_FILL_OPACITY = 0.12;

export const AXIS_TICK = { fill: CHROME.muted, fontSize: 11, fontFamily: MONO_STACK } as const;

export const xAxisProps = {
  tick: AXIS_TICK,
  tickLine: false,
  axisLine: { stroke: CHROME.rail },
  tickMargin: 8,
  minTickGap: 24,
} as const;

export const yAxisProps = {
  tick: AXIS_TICK,
  tickLine: false,
  axisLine: false,
  width: 48,
} as const;

/** Dashed grid reads as a threshold. */
export const gridProps = {
  stroke: CHROME.rail,
  strokeWidth: 1,
  vertical: false,
} as const;

export const cursorProps = { stroke: CHROME.rail, strokeWidth: 1 } as const;

export const CHART_MARGIN = { top: 8, right: 12, bottom: 0, left: 0 } as const;

function localeTag(): string {
  return i18n.language === 'uk' ? 'uk-UA' : 'en-US';
}

export function formatCompact(value: number): string {
  if (!Number.isFinite(value)) return '—';
  if (Math.abs(value) < 10_000) return value.toLocaleString(localeTag());
  return new Intl.NumberFormat(localeTag(), { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

export function formatRate(value: number): string {
  if (!Number.isFinite(value)) return '—';
  const rounded = Math.round(value * 10) / 10;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}

export function formatMs(value: number): string {
  if (!Number.isFinite(value)) return '—';
  return `${Math.round(value).toLocaleString(localeTag())}ms`;
}

export function share(part: number, whole: number): number {
  if (!whole || whole <= 0) return 0;
  return (part / whole) * 100;
}
