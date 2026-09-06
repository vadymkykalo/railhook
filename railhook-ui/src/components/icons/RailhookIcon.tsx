import { type SVGProps } from 'react';

/**
 * The Railhook mark: a hook whose tail turns up into a delivery arrow.
 *
 * Redrawn because the first version was built at 24px and collapsed into an
 * unreadable squiggle by the time it reached the 14–16px it is actually used
 * at — in the sidebar, the nav and inside the hero diagram. This one is drawn
 * on a 20-unit grid with a heavier stroke, a wider hook radius and the arrow
 * moved clear of the stem, so the two halves stay distinguishable when the
 * whole mark is 14 pixels across.
 */
/**
 * The mark's geometry on its own 20-unit grid, exported because the hero
 * diagram draws the same mark inline in SVG. It used to hand-roll a rounded
 * hook of its own, which read as a different logo sitting next to the real one.
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

export function RailhookIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.25"
      strokeLinecap="round"
      strokeLinejoin="round"
      {...props}
    >
      {/* The hook: down the stem, round the bend, back up */}
      <path d={RAILHOOK_MARK.hook} />
      {/* The flow: the tail leaving as an arrow */}
      <path d={RAILHOOK_MARK.flow} />
      {/* The origin */}
      <circle cx={RAILHOOK_MARK.origin.cx} cy={RAILHOOK_MARK.origin.cy} r={RAILHOOK_MARK.origin.r} fill="currentColor" stroke="none" />
    </svg>
  );
}
