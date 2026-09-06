import { useTranslation } from 'react-i18next';
import StatusBadge from '../StatusBadge';
import { cn } from '../../lib/utils';
import { SERIES, formatCompact, formatRate } from './chartTheme';
import { quotaKind } from './statusScale';

interface MeterProps {
  label: string;
  current: number;
  /** Zero or negative means the plan does not cap this resource. */
  limit: number;
  percentUsed: number;
  className?: string;
}

const FILL: Record<string, string> = {
  within: SERIES.brand,
  approaching: SERIES.retry,
  over: SERIES.halt,
};

/**
 * Quota against a limit.
 *
 * The unfilled track is a lighter step of the fill's own hue rather than a
 * neutral gray, so the state reads across the whole bar and not just the part
 * that happens to be filled. The bar stays in the brand hue while there is
 * room; it only borrows a status hue once someone has to do something about it
 * — approaching the limit is `retry`, past it is `halt`.
 */
export default function Meter({ label, current, limit, percentUsed, className }: MeterProps) {
  const { t } = useTranslation();
  const unlimited = !Number.isFinite(limit) || limit <= 0;
  const kind = unlimited ? 'within' : quotaKind(percentUsed);
  const filled = unlimited ? 0 : Math.min(Math.max(percentUsed, 0), 100);

  return (
    <div className={cn('rounded-xl border border-rail bg-card p-4 shadow-card', className)}>
      <div className="flex items-start justify-between gap-2">
        <span className="mono-label">{label}</span>
        {kind === 'approaching' && <StatusBadge kind="retry" label={t('usage.quota.approaching')} />}
        {kind === 'over' && <StatusBadge kind="halt" label={t('usage.quota.over')} />}
      </div>

      <p className="mt-2 text-2xl font-semibold leading-none tracking-tight">{formatCompact(current)}</p>
      <p className="mt-1.5 font-mono text-xs text-muted-foreground">
        {unlimited
          ? t('usage.quota.unlimited')
          : t('usage.quota.ofLimit', { limit: formatCompact(limit), percent: formatRate(percentUsed) })}
      </p>

      {!unlimited && (
        <div className="relative mt-3 h-2 w-full overflow-hidden rounded-full" role="presentation">
          <div className="absolute inset-0 rounded-full" style={{ backgroundColor: FILL[kind], opacity: 0.18 }} />
          <div
            className="absolute inset-y-0 left-0 rounded-full"
            style={{ width: `${filled}%`, backgroundColor: FILL[kind] }}
          />
        </div>
      )}
    </div>
  );
}
