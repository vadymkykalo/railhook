import { type SVGProps } from 'react';

import { RAILHOOK_MARK } from './railhookMark';

export { RAILHOOK_MARK };

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
