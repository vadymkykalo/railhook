import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { ArrowRight } from 'lucide-react';
import { cn } from '../lib/utils';

/**
 * The shape a rule takes on screen.
 *
 * A Rule and a PII masking rule are the same sentence: *when this matches, do
 * that*. They were drawn as two unrelated screens — one a stack of expanding
 * cards, the other a bare table — so a reader had to relearn the idea on the
 * second page. Both now use these pieces, and the sentence reads left to right:
 *
 *   status · name · what it matches → what it does · controls
 *
 * Action chips are deliberately colourless. An action is not a status, and the
 * four status hues are reserved; what an action means is carried by its icon
 * and its words.
 */

/** The counts strip above a rule list. Values are machine facts, so mono. */
export function RuleStats({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <div className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-rail bg-rail sm:grid-cols-4">
      {items.map((item) => (
        <div key={item.label} className="bg-card px-4 py-3">
          <div className="mono-label">{item.label}</div>
          <p className="mt-1 font-mono text-xl leading-none tabular-nums">{item.value}</p>
        </div>
      ))}
    </div>
  );
}

/** What a rule matches on: an event-type pattern, a JSON path, a field name. */
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

/** What a rule does when it matches. */
export function RuleActionChip({
  icon: Icon, label, detail,
}: {
  icon: LucideIcon;
  label: string;
  detail?: ReactNode;
}) {
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5 rounded-md border border-rail bg-secondary/60 px-2 py-1 text-xs">
      <Icon className="h-3 w-3 flex-shrink-0 text-muted-foreground" aria-hidden />
      <span className="truncate font-medium">{label}</span>
      {detail && <span className="truncate font-mono text-[11px] text-muted-foreground">{detail}</span>}
    </span>
  );
}

/** The arrow that turns two halves into one sentence. */
export function MatchArrow() {
  return <ArrowRight className="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" aria-hidden />;
}

/**
 * One rule. `match` and `then` are the two halves of the sentence; `status`
 * and `controls` bracket it.
 */
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
  /** A disabled rule reads back, not gone. */
  muted?: boolean;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'rounded-xl border border-rail bg-card shadow-card transition-shadow',
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
