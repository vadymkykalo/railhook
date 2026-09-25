import { type SVGProps } from 'react';

import { RAILHOOK_MARK } from './railhookMark';

export { RAILHOOK_MARK };

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
      <path d={RAILHOOK_MARK.hook} />
      <path d={RAILHOOK_MARK.flow} />
      <circle cx={RAILHOOK_MARK.origin.cx} cy={RAILHOOK_MARK.origin.cy} r={RAILHOOK_MARK.origin.r} fill="currentColor" stroke="none" />
    </svg>
  );
}
