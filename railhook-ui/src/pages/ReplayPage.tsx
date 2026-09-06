import { useState, useEffect, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import { History, Play, Square, Loader2, RefreshCw } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge, { type StatusKind } from '../components/StatusBadge';
import { projectsApi } from '../api/projects.api';
import { endpointsApi } from '../api/endpoints.api';
import { replayApi } from '../api/replay.api';
import type { ReplaySessionResponse, ReplayEstimateResponse } from '../api/replay.api';
import type { ProjectResponse, EndpointResponse } from '../types/api.types';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Select } from '../components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { FilterBar, FilterField, TimeCell } from './tableParts';
import Callout from '../components/Callout';

const QUICK_RANGES = ['1h', '6h', '24h', '7d', 'custom'] as const;

/**
 * A replay session is an action taken over a selection of events, so it borrows
 * the same four status meanings everything else uses rather than inventing a
 * palette: running is an attempt still owed, completed is delivered, failed is
 * abandoned, cancelled is nothing tried.
 */
function kindOfReplayStatus(status: string): StatusKind {
  switch (status) {
    case 'COMPLETED': return 'ok';
    case 'RUNNING':
    case 'PENDING':
    case 'ESTIMATING':
    case 'CANCELLING': return 'retry';
    case 'FAILED': return 'halt';
    default: return 'idle';
  }
}

function quickRange(key: string): { from: string; to: string } {
  const now = new Date();
  const to = now.toISOString();
  const spans: Record<string, number> = {
    '1h': 60 * 60 * 1000,
    '6h': 6 * 60 * 60 * 1000,
    '24h': 24 * 60 * 60 * 1000,
    '7d': 7 * 24 * 60 * 60 * 1000,
  };
  const span = spans[key] ?? spans['24h'];
  return { from: new Date(now.getTime() - span).toISOString(), to };
}

function toLocalDatetime(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fromLocalDatetime(local: string): string {
  return new Date(local).toISOString();
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  return `${Math.floor(s / 60)}m ${s % 60}s`;
}

export default function ReplayPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canReplayDeliveries } = usePermissions();

  const [project, setProject] = useState<ProjectResponse | null>(null);
  const [endpoints, setEndpoints] = useState<EndpointResponse[]>([]);
  const [sessions, setSessions] = useState<ReplaySessionResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);

  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [eventType, setEventType] = useState('');
  const [endpointId, setEndpointId] = useState('');
  const [selectedRange, setSelectedRange] = useState('24h');

  const [estimate, setEstimate] = useState<ReplayEstimateResponse | null>(null);
  const [estimating, setEstimating] = useState(false);

  const [showConfirm, setShowConfirm] = useState(false);
  const [creating, setCreating] = useState(false);
  const [pollingActive, setPollingActive] = useState(false);

  const loadSessions = useCallback(async () => {
    if (!projectId) return;
    try {
      const data = await replayApi.list(projectId, 0, 50);
      setSessions(data.content);
    } catch {
      // Silent — this also runs on a poll.
    }
  }, [projectId]);

  const loadData = useCallback(async () => {
    if (!projectId) return;
    try {
      setLoading(true);
      const [projectData, endpointsData, sessionsData] = await Promise.all([
        projectsApi.get(projectId),
        endpointsApi.list(projectId),
        replayApi.list(projectId, 0, 50),
      ]);
      setProject(projectData);
      setEndpoints(endpointsData);
      setSessions(sessionsData.content);
      setLoadError(null);
    } catch (err: any) {
      setLoadError(err);
      showApiError(err, 'replay.toast.loadFailed', { retry: loadData });
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { loadData(); }, [loadData]);

  useEffect(() => {
    const hasRunning = sessions.some(s =>
      s.status === 'RUNNING' || s.status === 'PENDING' || s.status === 'ESTIMATING' || s.status === 'CANCELLING'
    );
    setPollingActive(hasRunning);
    if (hasRunning) {
      const interval = setInterval(() => loadSessions(), 2000);
      return () => clearInterval(interval);
    }
  }, [sessions, loadSessions]);

  useEffect(() => {
    const range = quickRange('24h');
    setFromDate(toLocalDatetime(range.from));
    setToDate(toLocalDatetime(range.to));
  }, []);

  const handleQuickRange = (key: string) => {
    setSelectedRange(key);
    if (key !== 'custom') {
      const range = quickRange(key);
      setFromDate(toLocalDatetime(range.from));
      setToDate(toLocalDatetime(range.to));
    }
    setEstimate(null);
  };

  const handleEstimate = async () => {
    if (!projectId || !fromDate || !toDate) return;
    setEstimating(true);
    setEstimate(null);
    try {
      setEstimate(await replayApi.estimate(projectId, {
        fromDate: fromLocalDatetime(fromDate),
        toDate: fromLocalDatetime(toDate),
        eventType: eventType || undefined,
        endpointId: endpointId || undefined,
      }));
    } catch (err: any) {
      showApiError(err, 'replay.toast.estimateFailed');
    } finally {
      setEstimating(false);
    }
  };

  const handleCreate = async () => {
    if (!projectId || !fromDate || !toDate) return;
    setCreating(true);
    try {
      await replayApi.create(projectId, {
        fromDate: fromLocalDatetime(fromDate),
        toDate: fromLocalDatetime(toDate),
        eventType: eventType || undefined,
        endpointId: endpointId || undefined,
      });
      showSuccess(t('replay.toast.created'));
      setShowConfirm(false);
      setEstimate(null);
      loadSessions();
    } catch (err: any) {
      showApiError(err, 'replay.toast.createFailed');
    } finally {
      setCreating(false);
    }
  };

  const handleCancel = async (sessionId: string) => {
    if (!projectId) return;
    try {
      await replayApi.cancel(projectId, sessionId);
      showSuccess(t('replay.toast.cancelled'));
      loadSessions();
    } catch (err: any) {
      showApiError(err, 'replay.toast.cancelFailed');
    }
  };

  const endpointUrlOf = (id?: string) => {
    if (!id) return t('replay.session.allEndpoints');
    return endpoints.find(e => e.id === id)?.url || id.substring(0, 8);
  };

  if (loading && !project) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={3} height="h-20" cols="grid-cols-3" />
        <div className="h-[300px] animate-pulse rounded-xl bg-muted" />
      </PageSkeleton>
    );
  }

  const canStart = !!estimate && estimate.totalEvents > 0 && !estimate.warning;

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('nav.outgoing')}
        description={<Trans i18nKey="replay.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
      />

      <PermissionGate allowed={canReplayDeliveries}>
        <section className="mb-8 rounded-lg border border-rail bg-card p-4">
          <h3 className="mono-label mb-3">{t('replay.selection')}</h3>

          <div className="mb-3 flex flex-wrap gap-1.5">
            {QUICK_RANGES.map((key) => (
              <Button
                key={key}
                variant={selectedRange === key ? 'default' : 'outline'}
                size="sm"
                onClick={() => handleQuickRange(key)}
              >
                {t(`replay.quickRanges.${key}`)}
              </Button>
            ))}
          </div>

          <FilterBar className="mb-0">
            <FilterField id="replay-from" label={t('replay.filters.fromDate')} className="min-w-[13rem]">
              <Input
                id="replay-from"
                type="datetime-local"
                value={fromDate}
                onChange={(e) => { setFromDate(e.target.value); setSelectedRange('custom'); setEstimate(null); }}
              />
            </FilterField>
            <FilterField id="replay-to" label={t('replay.filters.toDate')} className="min-w-[13rem]">
              <Input
                id="replay-to"
                type="datetime-local"
                value={toDate}
                onChange={(e) => { setToDate(e.target.value); setSelectedRange('custom'); setEstimate(null); }}
              />
            </FilterField>
            <FilterField id="replay-event-type" label={t('replay.filters.eventType')}>
              <Input
                id="replay-event-type"
                placeholder={t('replay.filters.eventTypePlaceholder')}
                value={eventType}
                onChange={(e) => { setEventType(e.target.value); setEstimate(null); }}
              />
            </FilterField>
            <FilterField id="replay-endpoint" label={t('replay.filters.endpoint')} className="min-w-[14rem]">
              <Select id="replay-endpoint" value={endpointId} onChange={(e) => { setEndpointId(e.target.value); setEstimate(null); }}>
                <option value="">{t('replay.filters.allEndpoints')}</option>
                {endpoints.map(ep => (<option key={ep.id} value={ep.id}>{ep.url}</option>))}
              </Select>
            </FilterField>
          </FilterBar>

          {estimate && (
            <dl className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
              {[
                { label: t('replay.estimate_result.totalEvents'), value: estimate.totalEvents },
                { label: t('replay.estimate_result.estimatedDeliveries'), value: estimate.estimatedDeliveries },
                { label: t('replay.estimate_result.activeSubscriptions'), value: estimate.activeSubscriptions },
              ].map((metric) => (
                <div key={metric.label} className="rounded-lg border border-rail px-4 py-3">
                  <dt className="mono-label">{metric.label}</dt>
                  <dd className="mt-1 font-mono text-2xl">{metric.value.toLocaleString()}</dd>
                </div>
              ))}
            </dl>
          )}

          {estimate?.warning && (
            <Callout className="mt-3">{estimate.warning}</Callout>
          )}

          <div className="mt-4 flex flex-wrap justify-end gap-2">
            <Button variant="outline" onClick={handleEstimate} disabled={estimating || !fromDate || !toDate}>
              {estimating && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
              {estimating ? t('replay.estimating') : t('replay.estimate')}
            </Button>
            <VerificationGate>
              <Button onClick={() => setShowConfirm(true)} disabled={!canStart}>
                <Play className="h-3.5 w-3.5" />
                {estimate
                  ? t('replay.replayCount', { count: estimate.estimatedDeliveries })
                  : t('replay.startReplay')}
              </Button>
            </VerificationGate>
          </div>

          {!estimate && (
            <p className="mt-2 text-right text-xs text-muted-foreground">{t('replay.estimateFirst')}</p>
          )}
        </section>
      </PermissionGate>

      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-[15px] font-medium">{t('replay.history')}</h3>
        {pollingActive && (
          <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <RefreshCw className="h-3 w-3 animate-spin" aria-hidden />
            {t('replay.live')}
          </span>
        )}
      </div>

      {loadError ? (
        <ErrorState error={loadError} fallbackKey="replay.toast.loadFailed" onRetry={loadData} retrying={loading} />
      ) : sessions.length === 0 ? (
        <EmptyState
          icon={History}
          title={t('replay.noSessions')}
          description={t('replay.noSessionsDesc')}
          docsLink="/docs#deterministic-replay"
        />
      ) : (
        <div className="animate-fade-in overflow-hidden rounded-lg border border-rail bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('deliveries.columns.status')}</TableHead>
                <TableHead>{t('replay.session.timeRange')}</TableHead>
                <TableHead>{t('replay.session.eventType')}</TableHead>
                <TableHead>{t('replay.session.endpoint')}</TableHead>
                <TableHead>{t('replay.session.progress')}</TableHead>
                <TableHead>{t('replay.session.deliveries')}</TableHead>
                <TableHead>{t('replay.session.created')}</TableHead>
                <TableHead className="w-[90px]"><span className="sr-only">{t('common.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {sessions.map((session) => {
                const running = session.status === 'RUNNING' || session.status === 'PENDING' || session.status === 'CANCELLING';
                return (
                  <TableRow key={session.id}>
                    <TableCell>
                      <span className="flex flex-col items-start gap-1">
                        <StatusBadge kind={kindOfReplayStatus(session.status)} label={t(`replay.status.${session.status}`)} />
                        {session.errorMessage && (
                          <span className="block max-w-[200px] truncate text-[11px] text-halt" title={session.errorMessage}>
                            {session.errorMessage}
                          </span>
                        )}
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="font-mono text-[11px] text-muted-foreground">
                        {toLocalDatetime(session.fromDate).replace('T', ' ')} → {toLocalDatetime(session.toDate).replace('T', ' ')}
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="font-mono text-[13px]">{session.eventType || t('replay.session.allTypes')}</span>
                    </TableCell>
                    <TableCell>
                      <span className="block max-w-[180px] truncate font-mono text-[13px]" title={endpointUrlOf(session.endpointId)}>
                        {endpointUrlOf(session.endpointId)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="flex flex-col gap-1">
                        <span className="h-1.5 w-24 overflow-hidden rounded-full bg-secondary">
                          <span
                            className={`block h-full rounded-full ${session.status === 'COMPLETED' ? 'bg-ok' : 'bg-primary'}`}
                            style={{ width: `${Math.min(session.progressPercent ?? 0, 100)}%` }}
                          />
                        </span>
                        <span className="font-mono text-[11px] text-muted-foreground">
                          {session.processedEvents.toLocaleString()}/{session.totalEvents.toLocaleString()}
                          {session.durationMs ? ` · ${formatDuration(session.durationMs)}` : ''}
                        </span>
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="font-mono text-[13px]">{session.deliveriesCreated.toLocaleString()}</span>
                      {session.errors > 0 && (
                        <span className="ml-1.5 font-mono text-[11px] text-halt">
                          {t('replay.errorCount', { count: session.errors })}
                        </span>
                      )}
                    </TableCell>
                    <TableCell><TimeCell value={session.createdAt} /></TableCell>
                    <TableCell>
                      {running && canReplayDeliveries && (
                        <Button variant="outline" size="sm" onClick={() => handleCancel(session.id)}>
                          <Square className="h-3 w-3" />
                          {t('replay.cancel')}
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}

      <AlertDialog open={showConfirm} onOpenChange={setShowConfirm}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {estimate ? t('replay.replayCount', { count: estimate.estimatedDeliveries }) : t('replay.startReplay')}
            </AlertDialogTitle>
            <AlertDialogDescription>{t('replay.confirmStart')}</AlertDialogDescription>
          </AlertDialogHeader>
          {estimate && (
            <dl className="space-y-1 px-1 text-sm">
              <div className="flex justify-between">
                <dt className="text-muted-foreground">{t('replay.estimate_result.totalEvents')}</dt>
                <dd className="font-mono">{estimate.totalEvents.toLocaleString()}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-muted-foreground">{t('replay.estimate_result.estimatedDeliveries')}</dt>
                <dd className="font-mono">{estimate.estimatedDeliveries.toLocaleString()}</dd>
              </div>
            </dl>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={creating}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleCreate} disabled={creating}>
              {creating && <Loader2 className="h-4 w-4 animate-spin" />}
              {creating ? t('replay.starting') : t('replay.startReplay')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
