import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  AlertTriangle, ArrowRight, ArrowUpRight, BarChart3, Bell, Flame, FolderKanban, Plus, Radio, Send, Webhook,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import {
  useAnalytics, useDashboardStats, useDeliveries, useOpenIncidentCount,
  useProjects, useUnresolvedAlertCount,
} from '../api/queries';
import type { DeliveryFilters } from '../api/deliveries.api';
import { formatDateTime, formatDateTimeShort, formatRelativeTime, formatTime } from '../lib/date';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge, { kindOfDeliveryStatus } from '../components/StatusBadge';
import AttemptRail from '../components/AttemptRail';
import { railFromCounts } from './attemptRailData';
import GettingStarted from '../components/GettingStarted';
import { cn } from '../lib/utils';
import { Card } from '../components/ui/card';
import { Select } from '../components/ui/select';
import { Button } from '../components/ui/button';
import {
  ChartCard, OutcomeChart, STATUS_FILL, STATUS_TEXT, ShareBar, StatTile, coerceDeliveryStats,
  formatCompact, formatRate, kindOfSuccessRate, outcomeLegend, share, verdictOfDeliveryStats,
  type ShareSegment,
} from '../components/charts';
import type { StatusKind } from '../components/StatusBadge';

/** Stable so the deliveries query key does not change on every render. */
const IN_FLIGHT_FILTER: DeliveryFilters = { status: 'PENDING', page: 0, size: 4, sort: 'createdAt,desc' };

const DASHBOARD_PERIOD = '7d';

function SkeletonDashboard() {
  return (
    <PageSkeleton maxWidth="max-w-none">
      <SkeletonCards count={2} height="h-[292px]" cols="lg:grid-cols-2" />
      <SkeletonCards count={4} height="h-[104px]" cols="grid-cols-2 lg:grid-cols-4" />
    </PageSkeleton>
  );
}

/** One row of the "needs a human" list: a count, what it means, and where it lives. */
function AttentionRow({
  to, icon: Icon, label, count, kind,
}: {
  to: string;
  icon: React.ElementType;
  label: string;
  count: number;
  kind: StatusKind;
}) {
  const quiet = count === 0;
  return (
    <Link
      to={to}
      className="group flex items-center justify-between gap-3 rounded-lg px-2 py-2.5 transition-colors hover:bg-secondary/60"
    >
      <span className="flex min-w-0 items-center gap-2.5">
        <Icon
          className={cn('h-4 w-4 flex-shrink-0', quiet ? 'text-muted-foreground/50' : STATUS_TEXT[kind])}
          aria-hidden
        />
        <span className={quiet ? 'truncate text-sm text-muted-foreground' : 'truncate text-sm'}>{label}</span>
      </span>
      <span className="flex flex-shrink-0 items-center gap-1.5">
        <span
          className={cn(
            'font-mono text-sm tabular-nums',
            quiet ? 'text-muted-foreground' : cn('font-medium', STATUS_TEXT[kind])
          )}
        >
          {formatCompact(count)}
        </span>
        <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground/50 transition-colors group-hover:text-foreground" aria-hidden />
      </span>
    </Link>
  );
}

export default function DashboardPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();

  const {
    data: projects = [], isLoading: projectsLoading, isError: projectsIsError,
    error: projectsError, refetch: refetchProjects,
  } = useProjects();
  const [selectedProjectId, setSelectedProjectId] = useState<string>('');

  useEffect(() => {
    if (projects.length > 0 && !selectedProjectId) setSelectedProjectId(projects[0].id);
  }, [projects, selectedProjectId]);

  const projectId = selectedProjectId || undefined;

  const {
    data: dashboardStats, isLoading: statsLoading, isError: statsIsError,
    error: statsError, refetch: refetchStats,
  } = useDashboardStats(projectId);

  const {
    data: analytics, isLoading: analyticsLoading, isError: analyticsIsError,
    error: analyticsError, isFetching: analyticsFetching, refetch: refetchAnalytics,
  } = useAnalytics(projectId, DASHBOARD_PERIOD);

  const { data: inFlightPage } = useDeliveries(projectId, IN_FLIGHT_FILTER);
  const { data: unresolvedAlerts } = useUnresolvedAlertCount(projectId);
  const { data: openIncidents } = useOpenIncidentCount(projectId);

  const selectedProject = projects.find((p) => p.id === selectedProjectId);

  // The page's own failure. A project's stats or its charts failing is reported
  // inside the card that wanted them, not by blanking the whole dashboard.
  const pageIsError = projectsIsError || statsIsError;
  const retryPage = () => { refetchProjects(); refetchStats(); };

  // Every read of the payload goes through here: the dashboard is the first
  // screen a new account sees, and it has to render before the data does.
  const stats = coerceDeliveryStats(dashboardStats?.deliveryStats);
  const recentEvents = dashboardStats?.recentEvents ?? [];
  const endpointHealth = dashboardStats?.endpointHealth ?? [];

  const verdict = verdictOfDeliveryStats(stats);
  const series = useMemo(
    () => (analytics?.deliveryTimeSeries ?? []).map((p) => ({
      timestamp: p.timestamp,
      success: p.success ?? 0,
      failed: p.failed ?? 0,
    })),
    [analytics]
  );
  const totalSpark = useMemo(() => series.map((p) => p.success + p.failed), [series]);

  const outcomeLabels = {
    success: t('dashboard.outcome.delivered'),
    failed: t('dashboard.outcome.failed'),
  };

  const shareSegments: ShareSegment[] = [
    { key: 'delivered', label: t('dashboard.share.delivered'), value: stats.successfulDeliveries, token: 'ok' },
    { key: 'inFlight', label: t('dashboard.share.inFlight'), value: stats.pendingDeliveries, token: 'idle' },
    { key: 'failed', label: t('dashboard.share.failed'), value: stats.failedDeliveries, token: 'retry' },
    { key: 'abandoned', label: t('dashboard.share.abandoned'), value: stats.dlqDeliveries, token: 'halt' },
  ];

  const alertCount = unresolvedAlerts?.count ?? 0;
  const incidentCount = openIncidents?.count ?? 0;
  const attentionTotal = stats.dlqDeliveries + stats.failedDeliveries + alertCount + incidentCount;

  const inFlight = inFlightPage?.content ?? [];

  if (projectsLoading) return <SkeletonDashboard />;

  if (pageIsError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState
          error={projectsError ?? statsError}
          fallbackKey="dashboard.toast.loadFailed"
          onRetry={retryPage}
        />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={selectedProject?.name}
        title={t('dashboard.headline')}
        description={t('dashboard.headlineDesc')}
        actions={
          <>
            {projects.length > 1 && (
              <div className="w-48">
                <Select
                  aria-label={t('dashboard.projectPicker')}
                  value={selectedProjectId}
                  onChange={(e) => setSelectedProjectId(e.target.value)}
                >
                  {projects.map((project) => (
                    <option key={project.id} value={project.id}>{project.name}</option>
                  ))}
                </Select>
              </div>
            )}
            {selectedProjectId && (
              <Button variant="outline" size="sm" onClick={() => navigate(`/admin/projects/${selectedProjectId}/analytics`)}>
                <BarChart3 className="h-4 w-4" /> {t('dashboard.openAnalytics')}
              </Button>
            )}
          </>
        }
      />

      {selectedProject && <GettingStarted projectId={projectId} />}

      {!selectedProject ? (
        <EmptyState
          icon={FolderKanban}
          title={t('dashboard.noProjects')}
          description={t('dashboard.noProjectsDesc')}
          action={
            <Button onClick={() => navigate('/admin/projects')}>
              <Plus className="h-4 w-4" /> {t('dashboard.createProject')}
            </Button>
          }
        />
      ) : (
        <div className="animate-fade-in space-y-4">
          {/* The answer, then the evidence. */}
          <div className="grid gap-4 lg:grid-cols-3">
            <Card className="flex flex-col justify-between p-5">
              <div>
                <div className="mono-label">{t('dashboard.verdict.label')}</div>
                {statsLoading ? (
                  <div className="mt-3 h-12 w-32 animate-pulse rounded-lg bg-muted" aria-hidden />
                ) : (
                  <p
                    data-testid="delivery-health-figure"
                    className="mt-2 text-[3rem] font-semibold leading-none tracking-tight"
                  >
                    {stats.totalDeliveries > 0 ? `${formatRate(stats.successRate)}%` : '—'}
                  </p>
                )}
                <div className="mt-4">
                  <StatusBadge kind={verdict} label={t(`dashboard.verdict.${verdict}`)} />
                </div>
                <p className="mt-3 text-sm text-muted-foreground">
                  {stats.totalDeliveries > 0
                    ? t('dashboard.verdict.detail', {
                        delivered: formatCompact(stats.successfulDeliveries),
                        total: formatCompact(stats.totalDeliveries),
                      })
                    : t('dashboard.verdict.idleDetail')}
                </p>
              </div>
              <div className="mt-5 border-t border-rail pt-4">
                <ShareBar segments={shareSegments} total={Math.max(stats.totalDeliveries, 1)} />
              </div>
            </Card>

            <ChartCard
              className="lg:col-span-2"
              title={t('dashboard.outcome.title')}
              description={t('dashboard.outcome.desc')}
              eyebrow={DASHBOARD_PERIOD}
              legend={outcomeLegend(outcomeLabels)}
              bodyClass="h-[292px]"
              isLoading={analyticsLoading}
              error={analyticsIsError ? analyticsError : undefined}
              onRetry={() => refetchAnalytics()}
              isRefetching={analyticsFetching && !analyticsLoading}
              isEmpty={series.length === 0}
              emptyLabel={t('dashboard.outcome.empty')}
            >
              <OutcomeChart
                data={series}
                labels={outcomeLabels}
                formatTick={formatTime}
                formatStamp={formatDateTimeShort}
              />
            </ChartCard>
          </div>

          {/* The totals, after the answer rather than instead of it. */}
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <StatTile
              label={t('dashboard.stats.deliveries')}
              value={formatCompact(stats.totalDeliveries)}
              hint={t('dashboard.stats.window')}
              spark={totalSpark}
              to={`/admin/projects/${selectedProjectId}/deliveries`}
            />
            <StatTile
              label={t('dashboard.stats.delivered')}
              value={formatCompact(stats.successfulDeliveries)}
              hint={t('dashboard.stats.deliveredHint', { percent: formatRate(share(stats.successfulDeliveries, stats.totalDeliveries)) })}
              to={`/admin/projects/${selectedProjectId}/deliveries?status=SUCCESS`}
            />
            <StatTile
              label={t('dashboard.stats.inFlight')}
              value={formatCompact(stats.pendingDeliveries)}
              hint={t('dashboard.stats.inFlightHint')}
              to={`/admin/projects/${selectedProjectId}/deliveries?status=PENDING`}
            />
            <StatTile
              label={t('dashboard.stats.abandoned')}
              value={formatCompact(stats.dlqDeliveries)}
              hint={t('dashboard.stats.abandonedHint')}
              badge={stats.dlqDeliveries > 0 ? <StatusBadge kind="halt" label={t('dashboard.stats.dlqBadge')} icon={false} /> : undefined}
              to={`/admin/projects/${selectedProjectId}/dlq`}
            />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            {/* What needs a human. */}
            <Card className="p-5">
              <div className="mb-1 flex items-start justify-between gap-3">
                <div>
                  <h3 className="text-sm font-medium leading-tight">{t('dashboard.attention.title')}</h3>
                  <p className="mt-0.5 text-xs text-muted-foreground">{t('dashboard.attention.desc')}</p>
                </div>
                {attentionTotal === 0 && <StatusBadge kind="ok" label={t('dashboard.attention.clearBadge')} />}
              </div>
              <div className="mt-3 divide-y divide-rail">
                <AttentionRow
                  to={`/admin/projects/${selectedProjectId}/dlq`}
                  icon={AlertTriangle}
                  label={t('dashboard.attention.dlq')}
                  count={stats.dlqDeliveries}
                  kind="halt"
                />
                <AttentionRow
                  to={`/admin/projects/${selectedProjectId}/deliveries?status=FAILED`}
                  icon={Send}
                  label={t('dashboard.attention.failed')}
                  count={stats.failedDeliveries}
                  kind="retry"
                />
                <AttentionRow
                  to={`/admin/projects/${selectedProjectId}/alerts`}
                  icon={Bell}
                  label={t('dashboard.attention.alerts')}
                  count={alertCount}
                  kind="retry"
                />
                <AttentionRow
                  to={`/admin/projects/${selectedProjectId}/incidents`}
                  icon={Flame}
                  label={t('dashboard.attention.incidents')}
                  count={incidentCount}
                  kind="halt"
                />
              </div>
            </Card>

            {/* Anything still walking the ladder. */}
            <Card className="p-5">
              <div className="mb-3 flex items-start justify-between gap-3">
                <div>
                  <h3 className="text-sm font-medium leading-tight">{t('dashboard.inFlight.title')}</h3>
                  <p className="mt-0.5 text-xs text-muted-foreground">{t('dashboard.inFlight.desc')}</p>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-xs"
                  onClick={() => navigate(`/admin/projects/${selectedProjectId}/deliveries?status=PENDING`)}
                >
                  {t('common.viewAll')} <ArrowRight className="h-3 w-3" />
                </Button>
              </div>
              {inFlight.length === 0 ? (
                <p className="py-8 text-center text-sm text-muted-foreground">{t('dashboard.inFlight.empty')}</p>
              ) : (
                <ul className="space-y-3">
                  {inFlight.map((delivery) => {
                    const rail = railFromCounts(delivery.attemptCount, delivery.maxAttempts, delivery.status);
                    return (
                      <li key={delivery.id} className="flex items-center justify-between gap-4">
                        <span className="min-w-0">
                          <span className="block truncate font-mono text-xs text-foreground">{delivery.id}</span>
                          <span className="mt-0.5 block text-[11px] text-muted-foreground">
                            {formatRelativeTime(delivery.createdAt)}
                          </span>
                        </span>
                        <span className="flex flex-shrink-0 items-center gap-3">
                          <AttemptRail
                            attempts={rail.attempts}
                            maxAttempts={rail.maxAttempts}
                            ariaLabel={t('dashboard.inFlight.rail', {
                              count: delivery.attemptCount,
                              total: delivery.maxAttempts,
                            })}
                          />
                          <StatusBadge
                            kind={kindOfDeliveryStatus(delivery.status)}
                            label={t(`dashboard.inFlight.status.${delivery.status}`)}
                            icon={false}
                          />
                        </span>
                      </li>
                    );
                  })}
                </ul>
              )}
            </Card>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            {/* Endpoint health — the "where is it failing" half of the question. */}
            <Card className="p-5">
              <div className="mb-3 flex items-start justify-between gap-3">
                <div>
                  <h3 className="text-sm font-medium leading-tight">{t('dashboard.endpointHealth.title')}</h3>
                  <p className="mt-0.5 text-xs text-muted-foreground">{t('dashboard.endpointHealth.subtitle')}</p>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-xs"
                  onClick={() => navigate(`/admin/projects/${selectedProjectId}/endpoints`)}
                >
                  {t('common.viewAll')} <ArrowRight className="h-3 w-3" />
                </Button>
              </div>
              {statsLoading ? (
                <SkeletonCards count={3} height="h-12" cols="grid-cols-1" />
              ) : endpointHealth.length === 0 ? (
                <EmptyState
                  icon={Webhook}
                  title={t('dashboard.endpointHealth.empty')}
                  description={t('dashboard.endpointHealth.emptyDesc')}
                  className="flex flex-col items-center justify-center py-8"
                />
              ) : (
                <ul className="space-y-3">
                  {endpointHealth.slice(0, 5).map((endpoint) => {
                    const kind = kindOfSuccessRate(endpoint.successRate, endpoint.enabled);
                    return (
                      <li key={endpoint.id}>
                        <Link
                          to={`/admin/projects/${selectedProjectId}/endpoints`}
                          className="group block rounded-lg px-2 py-1.5 transition-colors hover:bg-secondary/60"
                        >
                          <span className="flex items-baseline justify-between gap-3">
                            <span className="truncate font-mono text-xs text-foreground">{endpoint.url}</span>
                            <span className={cn('flex-shrink-0 font-mono text-xs tabular-nums', STATUS_TEXT[kind])}>
                              {formatRate(endpoint.successRate)}%
                            </span>
                          </span>
                          <span className="mt-1.5 flex items-center gap-2">
                            <span className="relative h-1 flex-1 overflow-hidden rounded-full bg-muted">
                              <span
                                className={cn('absolute inset-y-0 left-0 rounded-full', STATUS_FILL[kind])}
                                style={{ width: `${Math.min(Math.max(endpoint.successRate, 0), 100)}%` }}
                              />
                            </span>
                            <span className="flex-shrink-0 font-mono text-[11px] text-muted-foreground">
                              {formatCompact(endpoint.totalDeliveries)}
                            </span>
                          </span>
                        </Link>
                      </li>
                    );
                  })}
                </ul>
              )}
            </Card>

            {/* What arrived. */}
            <Card className="p-5">
              <div className="mb-3 flex items-start justify-between gap-3">
                <div>
                  <h3 className="text-sm font-medium leading-tight">{t('dashboard.recentEvents.title')}</h3>
                  <p className="mt-0.5 text-xs text-muted-foreground">{t('dashboard.recentEvents.subtitle')}</p>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-xs"
                  onClick={() => navigate(`/admin/projects/${selectedProjectId}/events`)}
                >
                  {t('common.viewAll')} <ArrowRight className="h-3 w-3" />
                </Button>
              </div>
              {statsLoading ? (
                <SkeletonCards count={3} height="h-12" cols="grid-cols-1" />
              ) : recentEvents.length === 0 ? (
                <EmptyState
                  icon={Radio}
                  title={t('dashboard.recentEvents.empty')}
                  description={t('dashboard.recentEvents.emptyDesc')}
                  className="flex flex-col items-center justify-center py-8"
                />
              ) : (
                <ul className="space-y-1">
                  {recentEvents.slice(0, 5).map((event) => (
                    <li key={event.id}>
                      <Link
                        to={`/admin/projects/${selectedProjectId}/events`}
                        className="flex items-center justify-between gap-3 rounded-lg px-2 py-2 transition-colors hover:bg-secondary/60"
                      >
                        <span className="min-w-0">
                          <span className="block truncate font-mono text-xs text-foreground">{event.type}</span>
                          <span className="mt-0.5 block text-[11px] text-muted-foreground">
                            {formatDateTime(event.createdAt)}
                          </span>
                        </span>
                        <span className="flex-shrink-0 font-mono text-[11px] text-muted-foreground">
                          {t('dashboard.recentEvents.deliveryCount', { count: event.deliveryCount })}
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>
        </div>
      )}
    </div>
  );
}
