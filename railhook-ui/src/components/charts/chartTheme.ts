import i18n from '../../i18n';

/**
 * One chart system, one place that decides what a chart is allowed to paint.
 *
 * The colour rule this repo runs on is narrower than a normal chart palette:
 * `ok / retry / halt / idle` are reserved for status and nothing else, so a
 * series may only wear them when the series *means* delivered / still-owed /
 * abandoned / untried. Every other series — event types, endpoints, latency —
 * is nominal or ordinal and therefore takes the one non-status series hue the
 * product has: the brand teal. That is deliberate, not a shortage. A nominal
 * bar chart is one series (the title names it), so it needs exactly one hue;
 * an ordered scale (p50 → p99) takes that same hue stepped in lightness so the
 * reader sees the order in the colour.
 *
 * Everything resolves through CSS variables, so dark mode is not a second
 * palette — it is the same tokens read against a different surface.
 */

export const SERIES = {
  /** Delivery outcome. Reserved: only ever for what the token names. */
  ok: 'hsl(var(--ok))',
  retry: 'hsl(var(--retry))',
  halt: 'hsl(var(--halt))',
  idle: 'hsl(var(--idle))',
  /** The one non-status series hue. Slot 1, and there is no slot 2. */
  brand: 'hsl(var(--primary))',
} as const;

export type SeriesToken = keyof typeof SERIES;

/** Chrome. Recessive by contract: hairline, solid, one step off the surface. */
export const CHROME = {
  rail: 'hsl(var(--rail))',
  surface: 'hsl(var(--card))',
  ink: 'hsl(var(--foreground))',
  muted: 'hsl(var(--muted-foreground))',
} as const;

export const MONO_STACK = 'JetBrains Mono, ui-monospace, SFMono-Regular, monospace';

/**
 * The ordinal ramp: one hue, monotone lightness, adjacent steps a visible
 * distance apart, light end still readable on the surface.
 *
 * Opacity rather than a second set of hex values, so the ramp re-anchors
 * itself when the surface flips to dark. These five steps were searched, not
 * guessed: they are the lightest opening that clears OKLCH ΔL >= 0.06 between
 * every neighbour *and* the 2:1 light-end floor in both modes — 2.20:1 on
 * paper, 2.78:1 on ink.
 */
export const ORDINAL_STEPS = [0.49, 0.61, 0.74, 0.87, 1] as const;

export function ordinalStep(index: number, count: number): number {
  if (count <= 1) return 1;
  const slot = Math.round((index / (count - 1)) * (ORDINAL_STEPS.length - 1));
  return ORDINAL_STEPS[Math.min(Math.max(slot, 0), ORDINAL_STEPS.length - 1)];
}

/** Area fills are a wash, never a saturated block. */
export const AREA_FILL_OPACITY = 0.12;

/** Shared axis chrome. Mono ticks — an axis value is something the product said. */
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

/** Solid hairline, horizontal only. Dashed grid reads as "threshold"; it is not one. */
export const gridProps = {
  stroke: CHROME.rail,
  strokeWidth: 1,
  vertical: false,
} as const;

export const cursorProps = { stroke: CHROME.rail, strokeWidth: 1 } as const;

/** Margins that leave the x-axis band room, so a card never grows a nested scrollbar. */
export const CHART_MARGIN = { top: 8, right: 12, bottom: 0, left: 0 } as const;

function localeTag(): string {
  return i18n.language === 'uk' ? 'uk-UA' : 'en-US';
}

/** Stat-tile and axis values: 1,284 → 12.9K → 4.2M. */
export function formatCompact(value: number): string {
  if (!Number.isFinite(value)) return '—';
  if (Math.abs(value) < 10_000) return value.toLocaleString(localeTag());
  return new Intl.NumberFormat(localeTag(), { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

/** Rates carry one decimal only when they need it: 99.2% but 100%. */
export function formatRate(value: number): string {
  if (!Number.isFinite(value)) return '—';
  const rounded = Math.round(value * 10) / 10;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}

export function formatMs(value: number): string {
  if (!Number.isFinite(value)) return '—';
  return `${Math.round(value).toLocaleString(localeTag())}ms`;
}

/** Share of a whole, guarding the divide-by-zero a brand-new project always is. */
export function share(part: number, whole: number): number {
  if (!whole || whole <= 0) return 0;
  return (part / whole) * 100;
}
