import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { useAnalytics, queryKeys } from '../api/queries';
import type { AnalyticsData } from '../api/dashboard.api';
import { formatDateTimeShort, formatTime } from '../lib/date';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { cn } from '../lib/utils';
import {
  BarRankChart, ChartCard, OutcomeChart, STATUS_TEXT, StatTile, TrendChart,
  formatCompact, formatMs, formatRate, kindOfEndpointStatus, kindOfSuccessRate, outcomeLegend,
  share, type RankDatum,
} from '../components/charts';

const PERIODS = ['24h', '7d', '30d'] as const;
type Period = (typeof PERIODS)[number];

const EMPTY_OVERVIEW: AnalyticsData['overview'] = {
  totalEvents: 0, totalDeliveries: 0, successfulDeliveries: 0, failedDeliveries: 0,
  successRate: 0, avgLatencyMs: 0, p50LatencyMs: 0, p95LatencyMs: 0, p99LatencyMs: 0,
  eventsPerSecond: 0, deliveriesPerSecond: 0,
};

const EMPTY_PERCENTILES: AnalyticsData['latencyPercentiles'] = {
  p50: 0, p75: 0, p90: 0, p95: 0, p99: 0, max: 0,
};

export default function AnalyticsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const [period, setPeriod] = useState<Period>('24h');
  const qc = useQueryClient();

  const {
    data: analytics, isLoading, isError, error, isFetching, refetch,
  } = useAnalytics(projectId, period);

  const refresh = () => {
    if (projectId) qc.invalidateQueries({ queryKey: queryKeys.dashboard.analytics(projectId, period) });
  };

  // Every field is read through a default: an analytics window with no traffic
  // in it comes back sparse, and a chart page must render the absence rather
  // than fall over on it.
  const overview = analytics?.overview ?? EMPTY_OVERVIEW;
  const percentiles = analytics?.latencyPercentiles ?? EMPTY_PERCENTILES;
  const endpointPerformance = analytics?.endpointPerformance ?? [];

  const outcomeSeries = useMemo(
    () => (analytics?.deliveryTimeSeries ?? []).map((p) => ({
      timestamp: p.timestamp,
      success: p.success ?? 0,
      failed: p.failed ?? 0,
    })),
    [analytics]
  );

  const latencySeries = useMemo(
    () => (analytics?.latencyTimeSeries ?? []).filter((p) => (p.avgLatencyMs ?? 0) > 0),
    [analytics]
  );

  // Nominal categories, ordered by magnitude for reading — not coloured by it.
  const eventTypeRows: RankDatum[] = useMemo(
    () => (analytics?.eventTypeBreakdown ?? [])
      .slice()
      .sort((a, b) => b.count - a.count)
      .slice(0, 8)
      .map((e) => ({ key: e.eventType, label: e.eventType, value: e.count })),
    [analytics]
  );

  // Ordered categories: p50 → p99 is a scale, so the colour carries the order.
  const percentileRows: RankDatum[] = useMemo(
    () => ([
      ['p50', percentiles.p50], ['p75', percentiles.p75], ['p90', percentiles.p90],
      ['p95', percentiles.p95], ['p99', percentiles.p99],
    ] as const).map(([label, value]) => ({ key: label, label, value: value ?? 0 })),
    [percentiles]
  );

  const outcomeLabels = {
    success: t('analytics.outcome.delivered'),
    failed: t('analytics.outcome.failed'),
  };

  if (isLoading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={4} height="h-[104px]" cols="grid-cols-2 lg:grid-cols-4" />
        <SkeletonCards count={2} height="h-[300px]" cols="lg:grid-cols-2" />
      </PageSkeleton>
    );
  }

  if (isError || !analytics) {
    return (
      <div className="p-4 lg:p-6">
        <PageHeader eyebrow={period} description={t('analytics.subtitle')} />
        <ErrorState error={error} fallbackKey="analytics.loadFailed" onRetry={() => refetch()} />
      </div>
    );
  }

  const hasDeliveries = overview.totalDeliveries > 0;

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={period}
        description={t('analytics.subtitle')}
        actions={
          <Button variant="outline" size="sm" onClick={refresh} disabled={isFetching}>
            <RefreshCw className={cn('h-4 w-4', isFetching && 'animate-spin')} aria-hidden />
            {t('analytics.refresh')}
          </Button>
        }
      />

      {/* One filter row, above everything it scopes. Every chart below reads the
          same slice, so no two numbers on this page can disagree. */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div
          role="group"
          aria-label={t('analytics.periodLabel')}
          className="inline-flex rounded-lg border border-rail bg-card p-0.5"
        >
          {PERIODS.map((p) => (
            <button
              key={p}
              type="button"
              onClick={() => setPeriod(p)}
              aria-pressed={period === p}
              className={cn(
                'rounded-md px-3 py-1.5 font-mono text-xs transition-colors',
                period === p
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              )}
            >
              {t(`analytics.periods.${p}`)}
            </button>
          ))}
        </div>
      </div>

      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <StatTile
            label={t('analytics.successRate')}
            value={hasDeliveries ? `${formatRate(overview.successRate)}%` : '—'}
            hint={t('analytics.tiles.successRateHint', {
              delivered: formatCompact(overview.successfulDeliveries),
              total: formatCompact(overview.totalDeliveries),
            })}
          />
          <StatTile
            label={t('analytics.avgLatency')}
            value={hasDeliveries ? formatMs(overview.avgLatencyMs) : '—'}
            hint={t('analytics.tiles.latencyHint', {
              p95: formatMs(overview.p95LatencyMs),
              p99: formatMs(overview.p99LatencyMs),
            })}
          />
          <StatTile
            label={t('analytics.throughput')}
            value={overview.deliveriesPerSecond.toFixed(2)}
            hint={t('analytics.deliveriesPerSec')}
          />
          <StatTile
            label={t('analytics.failed')}
            value={formatCompact(overview.failedDeliveries)}
            hint={t('analytics.tiles.failedHint', {
              percent: formatRate(share(overview.failedDeliveries, overview.totalDeliveries)),
            })}
            badge={overview.failedDeliveries > 0 ? <StatusBadge kind="retry" label={t('analytics.tiles.failedBadge')} icon={false} /> : undefined}
          />
        </div>

        <ChartCard
          title={t('analytics.outcome.title')}
          description={t('analytics.outcome.desc')}
          eyebrow={period}
          legend={outcomeLegend(outcomeLabels)}
          bodyClass="h-[300px]"
          isRefetching={isFetching}
          isEmpty={outcomeSeries.length === 0}
          emptyLabel={t('analytics.noDeliveryData')}
        >
          <OutcomeChart
            data={outcomeSeries}
            labels={outcomeLabels}
            formatTick={formatTime}
            formatStamp={formatDateTimeShort}
          />
        </ChartCard>

        <div className="grid gap-4 lg:grid-cols-2">
          <ChartCard
            title={t('analytics.responseLatency')}
            description={t('analytics.responseLatencyDesc')}
            eyebrow={period}
            bodyClass="h-[260px]"
            isRefetching={isFetching}
            isEmpty={latencySeries.length === 0}
            emptyLabel={t('analytics.noLatencyData')}
          >
            <TrendChart
              data={latencySeries as unknown as Record<string, unknown>[]}
              dataKey="avgLatencyMs"
              seriesLabel={t('analytics.latencySeries')}
              formatTick={formatTime}
              formatStamp={formatDateTimeShort}
              formatValue={formatMs}
            />
          </ChartCard>

          <ChartCard
            title={t('analytics.eventTypes')}
            description={t('analytics.eventTypesDesc')}
            eyebrow={period}
            bodyClass="h-[260px]"
            isRefetching={isFetching}
            isEmpty={eventTypeRows.length === 0}
            emptyLabel={t('analytics.noEventsRecorded')}
          >
            <BarRankChart
              data={eventTypeRows}
              seriesLabel={t('analytics.eventTypesSeries')}
              categoryWidth={148}
            />
          </ChartCard>
        </div>

        <div className="grid gap-4 lg:grid-cols-3">
          <ChartCard
            title={t('analytics.latencyPercentiles')}
            description={t('analytics.latencyPercentilesDesc')}
            eyebrow={period}
            /* Five fixed rungs — sized to the rows so the card never scrolls. */
            bodyClass="h-[166px]"
            isRefetching={isFetching}
            isEmpty={!hasDeliveries}
            emptyLabel={t('analytics.noLatencyPercentiles')}
          >
            <BarRankChart
              data={percentileRows}
              seriesLabel={t('analytics.latencySeries')}
              formatValue={formatMs}
              ordinal
              categoryWidth={44}
            />
          </ChartCard>

          <Card className="overflow-hidden lg:col-span-2">
            <div className="px-5 pb-3 pt-5">
              <div className="mono-label mb-1">{period}</div>
              <h3 className="text-sm font-medium leading-tight">{t('analytics.endpointPerformance')}</h3>
              <p className="mt-0.5 text-xs text-muted-foreground">{t('analytics.endpointPerformanceDesc')}</p>
            </div>
            {endpointPerformance.length === 0 ? (
              <p className="px-5 pb-8 pt-4 text-center text-sm text-muted-foreground">
                {t('analytics.noEndpointData')}
              </p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('analytics.epColumns.endpoint')}</TableHead>
                    <TableHead className="w-[120px]">{t('analytics.epColumns.status')}</TableHead>
                    <TableHead className="w-[110px] text-right">{t('analytics.epColumns.deliveries')}</TableHead>
                    <TableHead className="w-[100px] text-right">{t('analytics.epColumns.success')}</TableHead>
                    <TableHead className="w-[100px] text-right">{t('analytics.epColumns.latency')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {endpointPerformance.map((ep) => (
                    <TableRow key={ep.endpointId}>
                      <TableCell className="max-w-0">
                        <Link
                          to={`/admin/projects/${projectId}/deliveries?endpointId=${ep.endpointId}`}
                          className="block truncate font-mono text-xs hover:underline"
                        >
                          {ep.url}
                        </Link>
                      </TableCell>
                      <TableCell>
                        <StatusBadge
                          kind={kindOfEndpointStatus(ep.status)}
                          label={t(`analytics.endpointStatus.${ep.status}`)}
                          icon={false}
                        />
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {formatCompact(ep.totalDeliveries)}
                      </TableCell>
                      <TableCell
                        className={cn(
                          'text-right font-mono text-xs tabular-nums',
                          STATUS_TEXT[kindOfSuccessRate(ep.successRate, ep.enabled)]
                        )}
                      >
                        {formatRate(ep.successRate)}%
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums text-muted-foreground">
                        {formatMs(ep.avgLatencyMs)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}
