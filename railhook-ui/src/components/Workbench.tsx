import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { Loader2, Play } from 'lucide-react';
import { cn } from '../lib/utils';
import { Button } from './ui/button';
import StatusBadge, { type StatusKind } from './StatusBadge';

/**
 * The workbench shape.
 *
 * Test console, Transform studio and Event diff are the same job three times:
 * give it an input, run it, read the output. They used to invent three
 * arrangements of that — one with the run button buried in a card footer, one
 * with it between two accordions, one with no run button at all — so this
 * component states the shape once.
 *
 *   input on the left · one unmistakable run control under it · result on the right
 *
 * The result column leads with its verdict: a `StatusBadge` and a row of mono
 * metrics, so the answer is legible before anything is scrolled. Detail —
 * payloads, attempts, headers — hangs below that strip.
 */

export function Workbench({
  input, run, result, className,
}: {
  input: ReactNode;
  /** The single primary control. Rendered directly under the input column. */
  run?: ReactNode;
  result: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]', className)}>
      <div className="min-w-0 space-y-4">
        {input}
        {run}
      </div>
      <div className="min-w-0 space-y-4">{result}</div>
    </div>
  );
}

/** A titled slab. The eyebrow is mono because it names a part of the machine. */
export function WorkbenchPanel({
  eyebrow, title, description, actions, children, className, bodyClassName,
}: {
  eyebrow?: ReactNode;
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <section className={cn('rounded-xl border border-rail bg-card shadow-card', className)}>
      {(title || eyebrow || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-2 border-b border-rail px-4 py-2.5">
          <div className="min-w-0">
            {eyebrow && <div className="mono-label">{eyebrow}</div>}
            {title && <h3 className="truncate text-[13px] font-medium">{title}</h3>}
            {description && <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>}
          </div>
          {actions && <div className="flex flex-shrink-0 items-center gap-1">{actions}</div>}
        </header>
      )}
      <div className={cn('p-4', bodyClassName)}>{children}</div>
    </section>
  );
}

/**
 * The run control. There is exactly one per workbench page and it always looks
 * the same, so "how do I make this thing go" is never a question.
 */
export function RunControl({
  label, runningLabel, running, disabled, onClick, type = 'button', icon: Icon = Play, hint, secondary,
}: {
  label: string;
  runningLabel: string;
  running?: boolean;
  disabled?: boolean;
  onClick?: () => void;
  type?: 'button' | 'submit';
  icon?: LucideIcon;
  /** One short line under the button — what will happen, or why it is disabled. */
  hint?: ReactNode;
  /** Anything that is not the primary action (reset, load a sample). */
  secondary?: ReactNode;
}) {
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type={type}
          size="lg"
          onClick={onClick}
          disabled={disabled || running}
          className="flex-1 min-w-[180px]"
        >
          {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Icon className="h-4 w-4" />}
          {running ? runningLabel : label}
        </Button>
        {secondary}
      </div>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

/**
 * The verdict strip plus whatever detail belongs under it. `kind` comes from
 * the page's own mapping onto the four status meanings — never a colour the
 * page picked.
 */
export function ResultFrame({
  kind, statusLabel, title, metrics, actions, children, className,
}: {
  kind: StatusKind;
  statusLabel: string;
  title: ReactNode;
  metrics?: ReactNode;
  actions?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn('rounded-xl border border-rail bg-card shadow-card', className)}>
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-rail px-4 py-3">
        <div className="flex min-w-0 items-center gap-2.5">
          <StatusBadge kind={kind} label={statusLabel} />
          <h3 className="truncate text-[13px] font-medium">{title}</h3>
        </div>
        {actions && <div className="flex flex-shrink-0 items-center gap-1">{actions}</div>}
      </header>
      {metrics && (
        <div className="grid grid-cols-2 divide-x divide-rail border-b border-rail sm:grid-cols-3">
          {metrics}
        </div>
      )}
      {children && <div className="space-y-4 p-4">{children}</div>}
    </section>
  );
}

/** One number that matters, in the machine voice. */
export function ResultMetric({
  label, value, unit,
}: {
  label: string;
  value: ReactNode;
  unit?: string;
}) {
  return (
    <div className="px-4 py-3">
      <div className="mono-label">{label}</div>
      <p className="mt-1 font-mono text-lg leading-none tabular-nums">
        {value}
        {unit && <span className="ml-0.5 text-xs font-normal text-muted-foreground">{unit}</span>}
      </p>
    </div>
  );
}

/** The right column before anything has been run. Quiet — the action is left. */
export function ResultPlaceholder({
  icon: Icon, title, hint,
}: {
  icon: LucideIcon;
  title: string;
  hint?: string;
}) {
  return (
    <div className="flex min-h-[320px] flex-col items-center justify-center rounded-xl border border-dashed border-rail px-6 text-center">
      <div className="mb-4 flex h-11 w-11 items-center justify-center rounded-lg border border-rail bg-card">
        <Icon className="h-5 w-5 text-muted-foreground" />
      </div>
      <p className="text-[15px] font-medium">{title}</p>
      {hint && <p className="mt-1 max-w-xs text-sm text-muted-foreground">{hint}</p>}
    </div>
  );
}

/** A labelled, copyable block of machine output. */
export function OutputBlock({
  label, actions, children, className,
}: {
  label: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('overflow-hidden rounded-lg border border-rail', className)}>
      <div className="flex items-center justify-between gap-2 border-b border-rail bg-muted/40 px-2.5 py-1.5">
        <span className="mono-label">{label}</span>
        {actions}
      </div>
      {children}
    </div>
  );
}

/** A segmented control: which mode of one workbench, not navigation. */
export function ModeSwitch<T extends string>({
  value, onChange, options, ariaLabel,
}: {
  value: T;
  onChange: (value: T) => void;
  options: { value: T; label: string; icon?: LucideIcon }[];
  ariaLabel: string;
}) {
  return (
    <div role="group" aria-label={ariaLabel} className="flex gap-1 rounded-lg border border-rail bg-muted/50 p-1">
      {options.map((option) => {
        const Icon = option.icon;
        const active = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(option.value)}
            className={cn(
              'flex flex-1 items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-[13px] transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              active
                ? 'bg-card font-medium text-foreground shadow-card'
                : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {Icon && <Icon className="h-3.5 w-3.5" />}
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
