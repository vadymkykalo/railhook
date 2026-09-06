import { type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Copy, Search, X } from 'lucide-react';
import AttemptRail, { type RailAttempt } from '../components/AttemptRail';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { formatRelativeTime, formatDateTime, formatRelativeFuture } from '../lib/date';
import { showSuccess } from '../lib/toast';
import { cn } from '../lib/utils';

/**
 * The pieces every record list on this section shares.
 *
 * Events, Deliveries, the DLQ and Incoming events are four views of two
 * obligations, so they are one table shape rather than four: the same id cell
 * you can actually copy, the same filter row above the table, the same
 * selection column and count. Outgoing and incoming differ in what they carry,
 * never in how a person reads them.
 */

/**
 * An id a person can use: a short mono prefix, a copy control, and — when the
 * record has a detail view — a link to it. Truncation without a copy control is
 * what made the old "ev0…" column useless.
 */
export function CopyId({
  value,
  to,
  chars = 8,
  onNavigate,
  className,
}: {
  value: string;
  to?: string;
  chars?: number;
  onNavigate?: () => void;
  className?: string;
}) {
  const { t } = useTranslation();
  const short = value.length > chars ? value.slice(0, chars) : value;

  return (
    <span className={cn('group/id inline-flex items-center gap-1', className)}>
      {to ? (
        <Link
          to={to}
          onClick={(e) => { e.stopPropagation(); onNavigate?.(); }}
          className="rounded font-mono text-[13px] text-foreground underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          title={value}
        >
          {short}
        </Link>
      ) : (
        <code className="font-mono text-[13px] text-muted-foreground" title={value}>{short}</code>
      )}
      <Button
        variant="ghost"
        size="icon-sm"
        className="h-6 w-6 opacity-0 transition-opacity focus-visible:opacity-100 group-hover/id:opacity-100 group-hover/row:opacity-100"
        onClick={(e) => {
          e.stopPropagation();
          navigator.clipboard.writeText(value);
          showSuccess(t('common.copied'));
        }}
        title={t('common.copyId')}
        aria-label={t('common.copyId')}
      >
        <Copy className="h-3 w-3" />
      </Button>
    </span>
  );
}

/** The filter row. Above the table, on the page ground — never inside a card. */
export function FilterBar({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn('mb-4 flex flex-wrap items-end gap-x-3 gap-y-2', className)}>
      {children}
    </div>
  );
}

export function FilterField({
  id, label, children, className,
}: {
  id: string;
  label: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('flex min-w-[9rem] flex-col gap-1.5', className)}>
      <Label htmlFor={id} className="mono-label">{label}</Label>
      {children}
    </div>
  );
}

export function SearchField({
  id, label, placeholder, value, onChange, className,
}: {
  id: string;
  label: string;
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  className?: string;
}) {
  const { t } = useTranslation();
  return (
    <FilterField id={id} label={label} className={cn('min-w-[13rem] flex-1', className)}>
      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
        <Input
          id={id}
          value={value}
          placeholder={placeholder}
          onChange={(e) => onChange(e.target.value)}
          className="pl-9 pr-9"
        />
        {value && (
          <Button
            variant="ghost"
            size="icon-sm"
            className="absolute right-1 top-1/2 h-7 w-7 -translate-y-1/2"
            onClick={() => onChange('')}
            title={t('common.clearSearch')}
            aria-label={t('common.clearSearch')}
          >
            <X className="h-3.5 w-3.5" />
          </Button>
        )}
      </div>
    </FilterField>
  );
}

/** Selection column control. Native checkbox, so it is keyboard- and SR-native. */
export function SelectBox({
  checked, indeterminate = false, onChange, label,
}: {
  checked: boolean;
  indeterminate?: boolean;
  onChange: () => void;
  label: string;
}) {
  return (
    <input
      type="checkbox"
      checked={checked}
      ref={(el) => { if (el) el.indeterminate = indeterminate && !checked; }}
      onChange={onChange}
      onClick={(e) => e.stopPropagation()}
      aria-label={label}
      className="h-4 w-4 cursor-pointer accent-primary"
    />
  );
}

/** What a selection can be done to, and to how many. */
export function SelectionBar({
  count, onClear, children,
}: {
  count: number;
  onClear: () => void;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  if (count === 0) return null;
  return (
    <div className="mb-3 flex flex-wrap items-center gap-3 rounded-lg border border-primary/30 bg-primary/5 px-3 py-2">
      <span className="font-mono text-[13px] text-foreground">
        {t('common.selectedCount', { count })}
      </span>
      <Button variant="ghost" size="sm" onClick={onClear}>{t('common.clearSelection')}</Button>
      <div className="ml-auto flex items-center gap-2">{children}</div>
    </div>
  );
}

/** Relative time over the exact stamp — both, because operators need both. */
export function TimeCell({ value }: { value: string }) {
  return (
    <span className="flex flex-col">
      <span className="text-[13px]">{formatRelativeTime(value)}</span>
      <span className="font-mono text-[11px] text-muted-foreground">{formatDateTime(value)}</span>
    </span>
  );
}

/**
 * The attempt rail as a table cell: how far along the ladder this obligation
 * is, and when the next rung falls due.
 */
export function AttemptCell({
  rail, maxAttempts, attemptCount, ladderLength, nextRetryAt,
}: {
  rail: RailAttempt[];
  maxAttempts: number;
  attemptCount: number;
  ladderLength: number;
  nextRetryAt?: string;
}) {
  const { t } = useTranslation();
  return (
    <span className="flex flex-col gap-0.5">
      <AttemptRail
        attempts={rail}
        maxAttempts={maxAttempts}
        size="inline"
        ariaLabel={t('deliveries.rail.label', { count: attemptCount, total: ladderLength })}
      />
      <span className="font-mono text-[11px] text-muted-foreground">
        {attemptCount}/{ladderLength}
        {nextRetryAt && ` · ${t('deliveries.rail.next', { time: formatRelativeFuture(nextRetryAt) })}`}
      </span>
    </span>
  );
}

/**
 * SortableTableHead renders a bare `<th>` rather than `TableHead`, so it does
 * not inherit the mono column-head style. Every sortable column in these
 * tables sits next to plain `TableHead` cells and has to match them.
 */
export const SORTABLE_HEAD_CLASS = 'h-9 font-mono text-[11px] uppercase tracking-[0.08em]';
