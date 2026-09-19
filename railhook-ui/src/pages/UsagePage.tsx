import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useProject, useUsageStats } from '../api/queries';
import { billingApi, type ResourceUsage } from '../api/billing.api';
import { formatDate } from '../lib/date';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import { ErrorState } from '../components/EmptyState';
import { Card } from '../components/ui/card';
import { Select } from '../components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { cn } from '../lib/utils';
import {
  ChartCard, Meter, OutcomeChart, STATUS_TEXT, ShareBar, StatTile, formatCompact,
  outcomeLegend, type ShareSegment,
} from '../components/charts';

const WINDOWS = [7, 30, 90] as const;

const NO_QUOTA: ResourceUsage = { current: 0, limit: 0, percentUsed: 0 };

export default function UsagePage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const [days, setDays] = useState<number>(30);

  const { data: project } = useProject(projectId);
  const {
    data: usage, isLoading, isError, error, isFetching, refetch,
  } = useUsageStats(projectId, days);

  // The organization's plan, not the project's traffic: the same key BillingPage
  // reads, so the two screens can never show different remaining quota.
  const { data: quota } = useQuery({
    queryKey: ['billing', 'usage'],
    queryFn: billingApi.getUsage,
  });

  const current = usage?.current;
  const history = useMemo(() => usage?.history ?? [], [usage]);

  const dailySeries = useMemo(
    () => history
      .slice()
      .sort((a, b) => a.date.localeCompare(b.date))
      .map((d) => ({
        timestamp: d.date,
        success: d.successfulDeliveries ?? 0,
        failed: (d.failedDeliveries ?? 0) + (d.dlqCount ?? 0),
      })),
    [history]
  );

  const outcomeLabels = {
    success: t('usage.traffic.delivered'),
    failed: t('usage.traffic.failed'),
  };

  const mix: ShareSegment[] = [
    { key: 'delivered', label: t('usage.mix.delivered'), value: current?.successfulDeliveries ?? 0, token: 'ok' },
    { key: 'inFlight', label: t('usage.mix.inFlight'), value: current?.pendingDeliveries ?? 0, token: 'idle' },
    { key: 'failed', label: t('usage.mix.failed'), value: current?.failedDeliveries ?? 0, token: 'retry' },
    { key: 'abandoned', label: t('usage.mix.abandoned'), value: current?.dlqDeliveries ?? 0, token: 'halt' },
  ];
  const mixTotal = Math.max(current?.totalDeliveries ?? 0, 1);

  if (isLoading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={4} height="h-[140px]" cols="grid-cols-2 lg:grid-cols-4" />
        <SkeletonCards count={2} height="h-[280px]" cols="lg:grid-cols-2" />
      </PageSkeleton>
    );
  }

  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <PageHeader eyebrow={project?.name} title={t('usage.title')} description={t('usage.description')} />
        <ErrorState error={error} fallbackKey="usage.loadFailed" onRetry={() => refetch()} />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={project?.name}
        title={t('usage.title')}
        description={t('usage.description')}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="w-44">
          <Select
            aria-label={t('usage.periodLabel')}
            value={String(days)}
            onChange={(e) => setDays(Number(e.target.value))}
          >
            {WINDOWS.map((w) => (
              <option key={w} value={w}>{t(`usage.periods.${w}d`)}</option>
            ))}
          </Select>
        </div>
      </div>

      <div className="space-y-4">
        {/* Quota against limit — the question this tab exists to answer. */}
        <section>
          <div className="mb-3">
            <h3 className="text-sm font-medium leading-tight">{t('usage.quota.title')}</h3>
            <p className="mt-0.5 text-xs text-muted-foreground">
              {quota
                ? t('usage.quota.desc', { from: formatDate(quota.periodStart), to: formatDate(quota.periodEnd) })
                : t('usage.quota.descPending')}
            </p>
          </div>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <Meter label={t('usage.quota.events')} {...(quota?.events ?? NO_QUOTA)} />
            <Meter label={t('usage.quota.endpoints')} {...(quota?.endpoints ?? NO_QUOTA)} />
            <Meter label={t('usage.quota.projects')} {...(quota?.projects ?? NO_QUOTA)} />
            <Meter label={t('usage.quota.members')} {...(quota?.members ?? NO_QUOTA)} />
          </div>
        </section>

        <div className="grid gap-4 lg:grid-cols-3">
          <ChartCard
            className="lg:col-span-2"
            title={t('usage.traffic.title')}
            description={t('usage.traffic.desc')}
            eyebrow={t(`usage.periods.${days}d`)}
            legend={outcomeLegend(outcomeLabels)}
            bodyClass="h-[280px]"
            isRefetching={isFetching}
            isEmpty={dailySeries.length === 0}
            emptyLabel={t('usage.traffic.empty')}
          >
            <OutcomeChart
              data={dailySeries}
              labels={outcomeLabels}
              formatTick={formatDate}
              formatStamp={formatDate}
            />
          </ChartCard>

          <Card className="p-5">
            <h3 className="text-sm font-medium leading-tight">{t('usage.mix.title')}</h3>
            <p className="mb-4 mt-0.5 text-xs text-muted-foreground">{t('usage.mix.desc')}</p>
            <ShareBar segments={mix} total={mixTotal} />
            <p className="mt-5 border-t border-rail pt-4 font-mono text-xs text-muted-foreground">
              {t('usage.mix.total', { total: formatCompact(current?.totalDeliveries ?? 0) })}
            </p>
          </Card>
        </div>

        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <StatTile label={t('usage.tiles.events')} value={formatCompact(current?.totalEvents ?? 0)} hint={t('usage.tiles.eventsHint')} />
          <StatTile label={t('usage.tiles.incoming')} value={formatCompact(current?.totalIncomingEvents ?? 0)} hint={t('usage.tiles.incomingHint')} />
          <StatTile label={t('usage.resources.endpoints')} value={formatCompact(current?.activeEndpoints ?? 0)} hint={t('usage.tiles.endpointsHint')} />
          <StatTile label={t('usage.resources.alertRules')} value={formatCompact(current?.activeAlertRules ?? 0)} hint={t('usage.tiles.alertRulesHint')} />
        </div>

        {/* The chart's table twin: every plotted value readable without hovering. */}
        <Card className="overflow-hidden">
          <div className="px-5 pb-3 pt-5">
            <h3 className="text-sm font-medium leading-tight">{t('usage.history.title')}</h3>
            <p className="mt-0.5 text-xs text-muted-foreground">{t('usage.history.desc')}</p>
          </div>
          {history.length === 0 ? (
            <p className="px-5 pb-8 pt-4 text-center text-sm text-muted-foreground">{t('usage.history.empty')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('usage.history.date')}</TableHead>
                  <TableHead className="text-right">{t('usage.history.events')}</TableHead>
                  <TableHead className="text-right">{t('usage.history.deliveries')}</TableHead>
                  <TableHead className="text-right">{t('usage.history.success')}</TableHead>
                  <TableHead className="text-right">{t('usage.history.failed')}</TableHead>
                  <TableHead className="text-right">{t('usage.history.dlq')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.map((day) => (
                  <TableRow key={day.date}>
                    <TableCell className="font-mono text-xs">{day.date}</TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">{formatCompact(day.eventsCount)}</TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">{formatCompact(day.deliveriesCount)}</TableCell>
                    <TableCell className={cn('text-right font-mono text-xs tabular-nums', STATUS_TEXT.ok)}>
                      {formatCompact(day.successfulDeliveries)}
                    </TableCell>
                    <TableCell className={cn('text-right font-mono text-xs tabular-nums', day.failedDeliveries > 0 ? STATUS_TEXT.retry : 'text-muted-foreground')}>
                      {formatCompact(day.failedDeliveries)}
                    </TableCell>
                    <TableCell className={cn('text-right font-mono text-xs tabular-nums', day.dlqCount > 0 ? STATUS_TEXT.halt : 'text-muted-foreground')}>
                      {formatCompact(day.dlqCount)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Card>
      </div>
    </div>
  );
}
