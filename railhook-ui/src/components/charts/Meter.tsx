import { useTranslation } from 'react-i18next';
import StatusBadge from '../StatusBadge';
import { cn } from '../../lib/utils';
import { SERIES, formatCompact, formatRate } from './chartTheme';
import { quotaKind } from './statusScale';

interface MeterProps {
  label: string;
  current: number;
  limit: number;
  percentUsed: number;
  className?: string;
}

const FILL: Record<string, string> = {
  within: SERIES.brand,
  approaching: SERIES.retry,
  over: SERIES.halt,
};

export default function Meter({ label, current, limit, percentUsed, className }: MeterProps) {
  const { t } = useTranslation();
  const unlimited = !Number.isFinite(limit) || limit <= 0;
  const kind = unlimited ? 'within' : quotaKind(percentUsed);
  const filled = unlimited ? 0 : Math.min(Math.max(percentUsed, 0), 100);

  return (
    <div className={cn('border border-rail bg-card p-4 shadow-card', className)}>
      <div className="flex items-start justify-between gap-2">
        <span className="mono-label">{label}</span>
        {kind === 'approaching' && <StatusBadge kind="retry" label={t('usage.quota.approaching')} />}
        {kind === 'over' && <StatusBadge kind="halt" label={t('usage.quota.over')} />}
      </div>

      <p className="mt-2 text-2xl font-medium leading-none tracking-tight">{formatCompact(current)}</p>
      <p className="mt-1.5 font-mono text-xs text-muted-foreground">
        {unlimited
          ? t('usage.quota.unlimited')
          : t('usage.quota.ofLimit', { limit: formatCompact(limit), percent: formatRate(percentUsed) })}
      </p>

      {!unlimited && (
        <div className="relative mt-3 h-2 w-full overflow-hidden" role="presentation">
          <div className="absolute inset-0" style={{ backgroundColor: FILL[kind], opacity: 0.18 }} />
          <div
            className="absolute inset-y-0 left-0"
            style={{ width: `${filled}%`, backgroundColor: FILL[kind] }}
          />
        </div>
      )}
    </div>
  );
}
