/**
 * The mark's geometry on its own 20-unit grid.
 *
 * Its own module, with no JSX in it, because three kinds of caller draw this mark and one of
 * them is not React: `RailhookIcon` renders it as a component, the landing page's diagrams draw
 * it inline in their own SVG, and `scripts/generate-og-images.mjs` writes it into a page for a
 * headless browser. The hero diagram used to hand-roll a rounded hook of its own, which read as
 * a different logo sitting next to the real one.
 */
export const RAILHOOK_MARK = {
  /** The hook: down the stem, round the bend, back up. */
  hook: 'M5.25 3.5v6.75a4 4 0 0 0 8 0V8.5',
  /** The flow: the tail leaving as an arrow. */
  flow: 'M10.25 6.25l3-2.75 3 2.75',
  /** The origin. */
  origin: { cx: 5.25, cy: 3.5, r: 1.75 },
  viewBox: 20,
} as const;
