import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../lib/utils';
import { Card } from '../ui/card';
import { ErrorState } from '../EmptyState';
import ChartLegend, { type LegendItem } from './ChartLegend';

interface ChartCardProps {
  title: string;
  description?: string;
  eyebrow?: ReactNode;
  action?: ReactNode;
  legend?: LegendItem[];
  /** Include the x-axis band, or the card grows a nested scrollbar instead of an axis. */
  bodyClass?: string;
  isLoading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  isEmpty?: boolean;
  emptyLabel?: string;
  isRefetching?: boolean;
  children: ReactNode;
  className?: string;
}

/** A failed request renders ErrorState: an unreachable backend must not look like no traffic. */
export default function ChartCard({
  title, description, eyebrow, action, legend, bodyClass = 'h-[260px]',
  isLoading, error, onRetry, isEmpty, emptyLabel, isRefetching, children, className,
}: ChartCardProps) {
  const { t } = useTranslation();

  return (
    <Card className={cn('flex flex-col overflow-hidden', className)}>
      <div className="flex flex-wrap items-start justify-between gap-3 px-5 pb-3 pt-5">
        <div className="min-w-0">
          {eyebrow && <div className="mono-label mb-1">{eyebrow}</div>}
          <h3 className="text-sm font-medium leading-tight">{title}</h3>
          {description && <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>}
        </div>
        {action && <div className="flex flex-shrink-0 items-center gap-2">{action}</div>}
      </div>

      {legend && legend.length > 1 && <ChartLegend items={legend} className="px-5 pb-3" />}

      <div className={cn('relative px-2 pb-4', bodyClass)}>
        {error ? (
          <ErrorState
            error={error}
            onRetry={onRetry}
            className="flex h-full flex-col items-center justify-center px-4 text-center"
            testId="chart-error"
          />
        ) : isLoading ? (
          <div className="h-full w-full animate-pulse bg-muted" aria-hidden />
        ) : isEmpty ? (
          <p className="flex h-full items-center justify-center px-4 text-center text-sm text-muted-foreground">
            {emptyLabel ?? t('charts.empty')}
          </p>
        ) : (
          <div className={cn('h-full w-full transition-opacity duration-200', isRefetching && 'opacity-50')}>
            {children}
          </div>
        )}
      </div>
    </Card>
  );
}
