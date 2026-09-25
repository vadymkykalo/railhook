import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { ArrowRight } from 'lucide-react';
import { cn } from '../lib/utils';

/** Action chips are colourless on purpose: the four status hues are reserved. */

export function RuleStats({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <div className="grid grid-cols-2 gap-px overflow-hidden border border-rail bg-rail sm:grid-cols-4">
      {items.map((item) => (
        <div key={item.label} className="bg-card px-4 py-3">
          <div className="mono-label">{item.label}</div>
          <p className="mt-1 font-mono text-xl leading-none tabular-nums">{item.value}</p>
        </div>
      ))}
    </div>
  );
}

export function MatchExpression({ children, title }: { children: ReactNode; title?: string }) {
  return (
    <code
      title={title}
      className="truncate rounded border border-rail bg-muted/60 px-1.5 py-0.5 font-mono text-[11px] text-foreground"
    >
      {children}
    </code>
  );
}

export function RuleActionChip({
  icon: Icon, label, detail,
}: {
  icon: LucideIcon;
  label: string;
  detail?: ReactNode;
}) {
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5 border border-rail bg-secondary/60 px-2 py-1 text-xs">
      <Icon className="h-3 w-3 flex-shrink-0 text-muted-foreground" aria-hidden />
      <span className="truncate font-medium">{label}</span>
      {detail && <span className="truncate font-mono text-[11px] text-muted-foreground">{detail}</span>}
    </span>
  );
}

export function MatchArrow() {
  return <ArrowRight className="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" aria-hidden />;
}

export function RuleRow({
  status, name, meta, match, then: thenPart, controls, footer, muted, className,
}: {
  status?: ReactNode;
  name: ReactNode;
  meta?: ReactNode;
  match?: ReactNode;
  then?: ReactNode;
  controls?: ReactNode;
  footer?: ReactNode;
  muted?: boolean;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'border border-rail bg-card shadow-card transition-shadow',
        muted && 'opacity-65',
        className,
      )}
    >
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 p-3.5">
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <div className="flex min-w-0 items-center gap-2">
            <span className="truncate text-[13px] font-medium">{name}</span>
            {meta}
          </div>
          {(match || thenPart) && (
            <div className="flex min-w-0 flex-wrap items-center gap-1.5">
              {match}
              {match && thenPart && <MatchArrow />}
              {thenPart}
            </div>
          )}
        </div>
        {status && <div className="flex-shrink-0">{status}</div>}
        {controls && <div className="flex flex-shrink-0 items-center gap-1">{controls}</div>}
      </div>
      {footer}
    </div>
  );
}
