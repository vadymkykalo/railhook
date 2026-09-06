import { useState, useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Copy, RefreshCw, Loader2, Clock, CheckCircle2, XCircle, Eye, SkipForward, Lightbulb, AlertTriangle, Info, ExternalLink, Timer, TrendingUp, TrendingDown, GitCompare, Flame, Plus } from 'lucide-react';
import { showApiError, showSuccess } from '../lib/toast';
import { formatDateTime, formatRelativeFuture } from '../lib/date';
import { useTranslation } from 'react-i18next';
import { deliveriesApi } from '../api/deliveries.api';
import { incidentsApi } from '../api/incidents.api';
import type { DryRunReplayResponse } from '../api/deliveries.api';
import type { DeliveryResponse, DeliveryAttemptResponse } from '../types/api.types';
import { Button } from '../components/ui/button';
import JsonBlock from '../components/JsonBlock';
import { formatJson } from '../lib/json';
import { Badge } from '../components/ui/badge';
import AttemptRail from '../components/AttemptRail';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { SkeletonRows } from '../components/PageSkeleton';
import StatusBadge, { kindOfDeliveryStatus } from '../components/StatusBadge';
import { railFromDeliveryAttempts } from './attemptRailData';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '../components/ui/sheet';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { classifyError } from '../lib/errorClassifier';

interface DeliveryDetailsSheetProps {
  deliveryId: string | null;
  open: boolean;
  onClose: () => void;
  onRefresh: () => void;
}

export default function DeliveryDetailsSheet({
  deliveryId,
  open,
  onClose,
  onRefresh,
}: DeliveryDetailsSheetProps) {
  const { t } = useTranslation();
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;
  const [delivery, setDelivery] = useState<DeliveryResponse | null>(null);
  const [attempts, setAttempts] = useState<DeliveryAttemptResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [attemptsLoading, setAttemptsLoading] = useState(false);
  const [showReplayDialog, setShowReplayDialog] = useState(false);
  const [replaying, setReplaying] = useState(false);
  const [dryRunResult, setDryRunResult] = useState<DryRunReplayResponse | null>(null);
  const [dryRunLoading, setDryRunLoading] = useState(false);
  const [replayFromStep, setReplayFromStep] = useState<number | null>(null);
  const [compareMode, setCompareMode] = useState(false);
  const [compareLeft, setCompareLeft] = useState<number | null>(null);
  const [compareRight, setCompareRight] = useState<number | null>(null);
  const [creatingIncident, setCreatingIncident] = useState(false);

  useEffect(() => {
    if (deliveryId && open) {
      loadDelivery();
      loadAttempts();
    }
  }, [deliveryId, open]); // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-refresh for active deliveries
  useEffect(() => {
    if (!delivery || !open) return;
    if (delivery.status !== 'PENDING' && delivery.status !== 'PROCESSING') return;
    const interval = setInterval(() => { loadDelivery(); loadAttempts(); }, 3000);
    return () => clearInterval(interval);
  }, [delivery?.status, open]); // eslint-disable-line react-hooks/exhaustive-deps

  const loadDelivery = async () => {
    if (!deliveryId) return;

    try {
      setLoading(true);
      const data = await deliveriesApi.get(deliveryId);
      setDelivery(data);
      setLoadError(null);
    } catch (err: any) {
      setLoadError(err);
      showApiError(err, 'deliveryDetails.toast.loadFailed', { retry: loadDelivery });
    } finally {
      setLoading(false);
    }
  };

  const loadAttempts = async () => {
    if (!deliveryId) return;

    try {
      setAttemptsLoading(true);
      const data = await deliveriesApi.getAttempts(deliveryId);
      setAttempts(data);
    } catch (err: any) {
      console.error('Failed to load delivery attempts:', err);
      setAttempts([]);
    } finally {
      setAttemptsLoading(false);
    }
  };

  const handleReplay = async () => {
    if (!deliveryId) return;

    setReplaying(true);
    try {
      if (replayFromStep !== null) {
        await deliveriesApi.replayFromAttempt(deliveryId, replayFromStep);
      } else {
        await deliveriesApi.replay(deliveryId);
      }
      showSuccess(t('deliveryDetails.toast.replaySuccess'));
      setShowReplayDialog(false);
      setReplayFromStep(null);
      setDryRunResult(null);
      onRefresh();
      loadDelivery();
      loadAttempts();
    } catch (err: any) {
      showApiError(err, 'deliveryDetails.toast.replayFailed');
    } finally {
      setReplaying(false);
    }
  };

  const handleDryRun = async () => {
    if (!deliveryId) return;

    setDryRunLoading(true);
    try {
      const result = await deliveriesApi.dryRunReplay(deliveryId);
      setDryRunResult(result);
    } catch (err: any) {
      showApiError(err, 'deliveryDetails.toast.dryRunFailed');
    } finally {
      setDryRunLoading(false);
    }
  };

  const handleCreateIncident = async () => {
    if (!delivery || !projectId) return;
    setCreatingIncident(true);
    try {
      const lastFailed = attempts.filter(a => a.errorMessage || (a.httpStatusCode && a.httpStatusCode >= 400)).pop();
      const title = `Delivery ${delivery.status}: ${delivery.id.substring(0, 8)}… → ${lastFailed?.httpStatusCode || 'error'}`;
      const incident = await incidentsApi.create(projectId, {
        title,
        severity: delivery.status === 'DLQ' ? 'CRITICAL' : 'WARNING',
      });
      await incidentsApi.addTimeline(projectId, incident.id, {
        entryType: 'FAILURE',
        title: `Delivery ${delivery.status} after ${delivery.attemptCount} attempts`,
        detail: lastFailed?.errorMessage || `HTTP ${lastFailed?.httpStatusCode}`,
        deliveryId: delivery.id,
        endpointId: delivery.endpointId,
      });
      showSuccess(t('deliveryDetails.toast.incidentCreated'));
    } catch (err: any) {
      showApiError(err, 'deliveryDetails.toast.incidentFailed');
    } finally {
      setCreatingIncident(false);
    }
  };

  const copyToClipboard = (value: string, label: string) => {
    navigator.clipboard.writeText(value);
    showSuccess(t('deliveryDetails.toast.copied', { label }));
  };

  // The measured ladder — real waits, real codes — from the attempts fetched above.
  const rail = delivery
    ? railFromDeliveryAttempts(attempts, delivery)
    : { attempts: [], maxAttempts: 0 };

  const getStatusBadge = (status: DeliveryResponse['status']) => (
    <StatusBadge kind={kindOfDeliveryStatus(status)} label={t(`deliveries.status.${status}`)} />
  );


  const getDiagnosisPanel = () => {
    if (!delivery || (delivery.status !== 'FAILED' && delivery.status !== 'DLQ')) return null;
    const failedAttempts = attempts.filter(a => a.errorMessage || (a.httpStatusCode && a.httpStatusCode >= 400));
    const lastFailed = failedAttempts[failedAttempts.length - 1];
    if (!lastFailed) return null;

    const classification = classifyError(lastFailed);
    const severityConfig = {
      error: { border: 'border-halt/30', bg: 'bg-halt-soft', icon: XCircle, iconColor: 'text-halt' },
      warning: { border: 'border-retry/30', bg: 'bg-retry-soft', icon: AlertTriangle, iconColor: 'text-retry' },
      info: { border: 'border-rail', bg: 'bg-secondary/50', icon: Info, iconColor: 'text-muted-foreground' },
    }[classification.severity];

    const SeverityIcon = severityConfig.icon;

    return (
      <Card className={`${severityConfig.border} ${severityConfig.bg}`}>
        <CardHeader className="pb-2">
          <CardTitle className="text-base flex items-center gap-2">
            <SeverityIcon className={`h-4 w-4 ${severityConfig.iconColor}`} />
            {t('deliveryDetails.diagnosis.title')}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div>
            <span className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              {t('deliveryDetails.diagnosis.category')}
            </span>
            <p className="text-sm font-semibold mt-0.5">{t(classification.labelKey)}</p>
          </div>
          <div>
            <span className="text-xs font-medium text-muted-foreground uppercase tracking-wider flex items-center gap-1">
              <Lightbulb className="h-3 w-3" />
              {t('deliveryDetails.diagnosis.suggestedFix')}
            </span>
            <p className="text-sm mt-0.5">{t(classification.fixKey)}</p>
          </div>

          {delivery.status === 'DLQ' && (
            <p className="text-xs italic text-muted-foreground">
              {t('deliveryDetails.diagnosis.noMoreRetries')}
            </p>
          )}
        </CardContent>
      </Card>
    );
  };

  return (
    <>
      <Sheet open={open} onOpenChange={onClose}>
        <SheetContent className="overflow-y-auto w-full sm:max-w-2xl">
          <SheetHeader>
            <SheetTitle>{t('deliveryDetails.title')}</SheetTitle>
            <SheetDescription>
              {t('deliveryDetails.description')}
            </SheetDescription>
          </SheetHeader>

          {loading ? (
            <div className="mt-6"><SkeletonRows count={4} height="h-20" /></div>
          ) : delivery ? (
            <div className="space-y-6 mt-6">
              {/* Status Banner */}
              {delivery.status === 'PROCESSING' && (
                <div className="flex items-center gap-3 rounded-lg border border-retry/30 bg-retry-soft p-3">
                  <Loader2 className="h-4 w-4 animate-spin text-retry" aria-hidden />
                  <span className="text-sm font-medium text-retry">{t('deliveries.statusExplain.PROCESSING')}</span>
                </div>
              )}
              {delivery.status === 'PENDING' && (
                <div className="flex items-center gap-3 rounded-lg border border-retry/30 bg-retry-soft p-3">
                  <Clock className="h-4 w-4 text-retry" aria-hidden />
                  <span className="text-sm font-medium text-retry">
                    {delivery.attemptCount > 0 && delivery.nextRetryAt
                      ? t('deliveries.statusExplain.PENDING_RETRY', { time: formatRelativeFuture(delivery.nextRetryAt) })
                      : t('deliveries.statusExplain.PENDING_NEW')}
                  </span>
                </div>
              )}
              {delivery.status === 'SUCCESS' && (
                <div className="flex items-center gap-3 rounded-lg border border-ok/30 bg-ok-soft p-3">
                  <CheckCircle2 className="h-4 w-4 text-ok" aria-hidden />
                  <span className="text-sm font-medium text-ok">{t('deliveries.statusExplain.SUCCESS')}</span>
                </div>
              )}
              {(delivery.status === 'FAILED' || delivery.status === 'DLQ') && (
                <div className="flex items-center gap-3 rounded-lg border border-halt/30 bg-halt-soft p-3">
                  <XCircle className="h-4 w-4 text-halt" aria-hidden />
                  <span className="text-sm font-medium text-halt">
                    {delivery.status === 'DLQ'
                      ? t('deliveries.statusExplain.DLQ', { count: delivery.attemptCount })
                      : t('deliveries.statusExplain.FAILED')}
                  </span>
                </div>
              )}

              {/* Trace Block */}
              <Card>
                <CardHeader className="pb-3">
                  <CardTitle className="text-sm font-medium text-muted-foreground uppercase tracking-wider">{t('deliveryDetails.trace')}</CardTitle>
                </CardHeader>
                <CardContent className="space-y-2">
                  {(() => {
                    const firstAttempt = attempts[0];
                    let requestId: string | null = null;
                    if (firstAttempt?.requestHeaders) {
                      try {
                        const headers = JSON.parse(firstAttempt.requestHeaders);
                        requestId = headers['X-Request-Id'] || headers['x-request-id'] || headers['X-Webhook-Id'] || headers['x-webhook-id'] || null;
                      } catch { /* ignore */ }
                    }
                    const traceItems = [
                      { label: t('deliveryDetails.deliveryId'), value: delivery.id, link: null },
                      { label: t('deliveryDetails.eventId'), value: delivery.eventId, link: projectId ? `/admin/projects/${projectId}/events/${delivery.eventId}` : null },
                      { label: t('deliveryDetails.endpointId'), value: delivery.endpointId, link: projectId ? `/admin/projects/${projectId}/endpoints` : null },
                      ...(delivery.subscriptionId ? [{ label: t('deliveryDetails.subscriptionId'), value: delivery.subscriptionId, link: projectId ? `/admin/projects/${projectId}/subscriptions` : null }] : []),
                      ...(requestId ? [{ label: t('deliveryDetails.requestId'), value: requestId, link: null }] : []),
                    ];
                    return traceItems.map(({ label, value, link }) => (
                      <div key={label} className="group flex items-center justify-between gap-2 p-2 -mx-2 rounded-md hover:bg-muted/50 transition-colors">
                        <span className="text-xs font-medium text-muted-foreground shrink-0">{label}</span>
                        <div className="flex items-center gap-1.5 min-w-0">
                          <code className="text-xs font-mono truncate" title={value}>{value}</code>
                          {link && (
                            <Link to={link} onClick={onClose} className="shrink-0 text-primary hover:text-primary/80 transition-colors" title={t('deliveryDetails.goTo')} aria-label={t('deliveryDetails.goTo')}>
                              <ExternalLink className="h-3 w-3" />
                            </Link>
                          )}
                          <Button
                            variant="ghost"
                            size="icon"
                            className="h-6 w-6 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity"
                            onClick={() => copyToClipboard(value, label)}
                            title={t('deliveryDetails.copyValue')}
                            aria-label={t('deliveryDetails.copyValue')}
                          >
                            <Copy className="h-3 w-3" />
                          </Button>
                        </div>
                      </div>
                    ));
                  })()}
                </CardContent>
              </Card>

              {/* Quick Actions — incident integration */}
              {projectId && (delivery.status === 'FAILED' || delivery.status === 'DLQ') && (
                <Card className="border-rail">
                  <CardContent className="p-4">
                    <div className="flex items-center gap-2 flex-wrap">
                      <Link to={`/admin/projects/${projectId}/incidents`} onClick={onClose}>
                        <Button variant="outline" size="sm">
                          <Flame className="mr-1.5 h-3.5 w-3.5 text-halt" aria-hidden />
                          {t('deliveryDetails.openIncidents')}
                        </Button>
                      </Link>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={handleCreateIncident}
                        disabled={creatingIncident}
                      >
                        {creatingIncident ? <Loader2 className="h-3.5 w-3.5 mr-1.5 animate-spin" /> : <Plus className="h-3.5 w-3.5 mr-1.5" />}
                        {t('deliveryDetails.createIncident')}
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              )}

              {/* Status & Progress */}
              <Card>
                <CardHeader className="pb-3">
                  <div className="flex items-center justify-between">
                    <CardTitle className="text-sm font-medium text-muted-foreground uppercase tracking-wider">{t('deliveryDetails.statusAndProgress')}</CardTitle>
                    {getStatusBadge(delivery.status)}
                  </div>
                </CardHeader>
                <CardContent className="space-y-3">
                  <div className="space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="text-sm text-muted-foreground">{t('deliveryDetails.attempts')}</span>
                      <span className="font-mono text-sm">
                        {delivery.attemptCount}/{delivery.maxAttempts}
                      </span>
                    </div>
                    {/* The ladder this delivery actually walked: HTTP code above each
                        tick, the wait that preceded it below, placed on a log scale. */}
                    <AttemptRail
                      attempts={rail.attempts}
                      maxAttempts={rail.maxAttempts}
                      size="full"
                      ariaLabel={t('deliveries.rail.label', { count: delivery.attemptCount, total: delivery.maxAttempts })}
                    />
                  </div>

                  <div className="border-t pt-3 space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="text-sm text-muted-foreground">{t('deliveryDetails.created')}</span>
                      <span className="text-sm">{formatDateTime(delivery.createdAt)}</span>
                    </div>

                    {delivery.lastAttemptAt && (
                      <div className="flex items-center justify-between">
                        <span className="text-sm text-muted-foreground">{t('deliveryDetails.lastAttemptAt')}</span>
                        <span className="text-sm">{formatDateTime(delivery.lastAttemptAt)}</span>
                      </div>
                    )}

                    {delivery.status === 'PENDING' && delivery.nextRetryAt && (
                      <div className="flex items-center justify-between">
                        <span className="text-sm text-muted-foreground">{t('deliveryDetails.nextRetry')}</span>
                        <div className="text-right">
                          <span className="text-sm font-semibold text-retry">{formatRelativeFuture(delivery.nextRetryAt)}</span>
                          <span className="text-[11px] text-muted-foreground block">{formatDateTime(delivery.nextRetryAt)}</span>
                        </div>
                      </div>
                    )}

                    {delivery.succeededAt && (
                      <div className="flex items-center justify-between">
                        <span className="text-sm text-muted-foreground">{t('deliveryDetails.succeededAt')}</span>
                        <span className="text-sm font-medium text-ok">{formatDateTime(delivery.succeededAt)}</span>
                      </div>
                    )}

                    {delivery.failedAt && (
                      <div className="flex items-center justify-between">
                        <span className="text-sm text-muted-foreground">{t('deliveryDetails.failedAt')}</span>
                        <span className="text-sm font-medium text-halt">{formatDateTime(delivery.failedAt)}</span>
                      </div>
                    )}
                  </div>
                </CardContent>
              </Card>

              {getDiagnosisPanel()}

              {/* Latency Sparkline — only when ≥2 attempts with duration data */}
              {attempts.filter(a => a.durationMs != null).length >= 2 && (() => {
                const durations = attempts.filter(a => a.durationMs != null).map(a => a.durationMs!);
                const maxDur = Math.max(...durations);
                return (
                  <Card>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm font-medium text-muted-foreground uppercase tracking-wider flex items-center gap-2">
                        <Timer className="h-3.5 w-3.5" />
                        {t('deliveryDetails.latencyTrend')}
                      </CardTitle>
                    </CardHeader>
                    <CardContent>
                      <div className="flex items-end gap-1.5 h-12">
                        {attempts.map((attempt, i) => {
                          if (attempt.durationMs == null) return null;
                          const pct = maxDur > 0 ? (attempt.durationMs / maxDur) * 100 : 0;
                          const isSuccess = attempt.httpStatusCode && attempt.httpStatusCode >= 200 && attempt.httpStatusCode < 300;
                          const prev = i > 0 ? attempts[i - 1] : null;
                          const prevDur = prev?.durationMs;
                          return (
                            <div key={attempt.id} className="flex flex-col items-center gap-0.5 flex-1 min-w-0">
                              <div className="flex items-center gap-0.5 text-[10px] text-muted-foreground">
                                {prevDur != null && attempt.durationMs > prevDur && <TrendingUp className="h-2.5 w-2.5 text-halt" aria-hidden />}
                                {prevDur != null && attempt.durationMs < prevDur && <TrendingDown className="h-2.5 w-2.5 text-ok" aria-hidden />}
                                <span className="font-mono">{attempt.durationMs}ms</span>
                              </div>
                              <div
                                className={`w-full rounded-t transition-all ${isSuccess ? 'bg-ok' : 'bg-halt'}`}
                                style={{ height: `${Math.max(4, pct)}%` }}
                                title={`#${attempt.attemptNumber}: ${attempt.durationMs}ms — ${attempt.httpStatusCode || 'no response'}`}
                              />
                              <span className="font-mono text-[9px] text-muted-foreground">#{attempt.attemptNumber}</span>
                            </div>
                          );
                        })}
                      </div>
                    </CardContent>
                  </Card>
                );
              })()}

              <Card>
                <CardHeader>
                  <div className="flex items-center justify-between">
                    <CardTitle className="text-lg">{t('deliveryDetails.deliveryAttempts')}</CardTitle>
                    {attempts.length >= 2 && (
                      <Button
                        variant={compareMode ? 'default' : 'outline'}
                        size="sm"
                        onClick={() => {
                          setCompareMode(!compareMode);
                          setCompareLeft(null);
                          setCompareRight(null);
                        }}
                      >
                        <GitCompare className="h-3.5 w-3.5 mr-1.5" />
                        {t('deliveryDetails.compareAttempts')}
                      </Button>
                    )}
                  </div>
                  {compareMode && (
                    <p className="text-xs text-muted-foreground mt-2">
                      {t('deliveryDetails.compareHint')}
                    </p>
                  )}
                </CardHeader>
                <CardContent>
                  {/* Inline attempt comparison panel */}
                  {compareMode && compareLeft !== null && compareRight !== null && (() => {
                    const left = attempts.find(a => a.attemptNumber === compareLeft);
                    const right = attempts.find(a => a.attemptNumber === compareRight);
                    if (!left || !right) return null;
                    const diffs: { field: string; left: string; right: string; changed: boolean }[] = [
                      { field: t('deliveryDetails.compare.httpStatus'), left: String(left.httpStatusCode ?? '—'), right: String(right.httpStatusCode ?? '—'), changed: left.httpStatusCode !== right.httpStatusCode },
                      { field: t('deliveryDetails.compare.duration'), left: left.durationMs != null ? `${left.durationMs}ms` : '—', right: right.durationMs != null ? `${right.durationMs}ms` : '—', changed: left.durationMs !== right.durationMs },
                      { field: t('deliveryDetails.compare.error'), left: left.errorMessage || '—', right: right.errorMessage || '—', changed: left.errorMessage !== right.errorMessage },
                    ];
                    const leftBody = left.responseBody || '';
                    const rightBody = right.responseBody || '';
                    const bodyChanged = leftBody !== rightBody;
                    return (
                      <div className="mb-4 space-y-3 rounded-lg border border-rail p-4">
                        <div className="flex items-center justify-between">
                          <span className="flex items-center gap-1.5 text-sm font-semibold">
                            <GitCompare className="h-4 w-4 text-primary" aria-hidden />
                            {t('deliveryDetails.compare.versus', {
                              left: t('deliveryDetails.attemptNumber', { number: compareLeft }),
                              right: t('deliveryDetails.attemptNumber', { number: compareRight }),
                            })}
                          </span>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => { setCompareLeft(null); setCompareRight(null); }}
                            title={t('deliveryDetails.compare.clear')}
                            aria-label={t('deliveryDetails.compare.clear')}
                          >
                            <XCircle className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                        <div className="space-y-1">
                          {diffs.map(d => (
                            <div key={d.field} className={`flex items-center justify-between rounded px-2 py-1.5 text-xs ${d.changed ? 'bg-secondary' : ''}`}>
                              <span className="w-24 shrink-0 font-medium text-muted-foreground">{d.field}</span>
                              <span className={`font-mono ${d.changed ? 'text-halt line-through' : 'text-muted-foreground'}`}>{d.left}</span>
                              {d.changed && <span className="mx-1 text-muted-foreground">→</span>}
                              {d.changed && <span className="font-mono text-ok">{d.right}</span>}
                            </div>
                          ))}
                        </div>
                        {bodyChanged && leftBody && rightBody && (
                          <details>
                            <summary className="text-xs text-muted-foreground cursor-pointer hover:text-foreground font-medium">
                              {t('deliveryDetails.responseBodyDiff')}
                            </summary>
                            <div className="grid grid-cols-2 gap-2 mt-2">
                              <pre className="max-h-32 overflow-x-auto rounded border border-halt/30 bg-halt-soft p-2 font-mono text-[10px]">
                                {formatJson(leftBody)}
                              </pre>
                              <pre className="max-h-32 overflow-x-auto rounded border border-ok/30 bg-ok-soft p-2 font-mono text-[10px]">
                                {formatJson(rightBody)}
                              </pre>
                            </div>
                          </details>
                        )}
                      </div>
                    );
                  })()}

                  {attemptsLoading ? (
                    <SkeletonRows count={3} height="h-16" />
                  ) : attempts.length === 0 ? (
                    <EmptyState
                      icon={Clock}
                      title={t('deliveryDetails.noAttempts')}
                      className="flex flex-col items-center justify-center py-8"
                    />
                  ) : (
                    <div className="relative">
                      {attempts.map((attempt, i) => {
                        const isSuccess = attempt.httpStatusCode && attempt.httpStatusCode >= 200 && attempt.httpStatusCode < 300;
                        const isFail = attempt.errorMessage || (attempt.httpStatusCode && attempt.httpStatusCode >= 400);
                        const prev = i > 0 ? attempts[i - 1] : null;
                        const timeGap = prev ? Math.round((new Date(attempt.createdAt).getTime() - new Date(prev.createdAt).getTime()) / 1000) : null;

                        return (
                          <div key={attempt.id} className="relative">
                            {/* Time gap indicator */}
                            {timeGap !== null && timeGap > 0 && (
                              <div className="flex items-center gap-2 py-1.5 pl-[18px]">
                                <div className="w-px h-4 bg-border" />
                                <span className="text-[10px] text-muted-foreground/60 italic">
                                  {timeGap >= 3600 ? `${Math.round(timeGap / 3600)}h` : timeGap >= 60 ? `${Math.round(timeGap / 60)}m` : `${timeGap}s`} {t('deliveryDetails.later')}
                                </span>
                              </div>
                            )}

                            {/* Timeline row */}
                            <div className="flex gap-3">
                              {/* Timeline dot + line */}
                              <div className="flex flex-col items-center pt-1">
                                <div className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full border-2 ${
                                  isSuccess
                                    ? 'border-ok bg-ok-soft'
                                    : isFail
                                      ? 'border-halt bg-halt-soft'
                                      : 'border-idle bg-idle-soft'
                                }`}>
                                  {isSuccess && <CheckCircle2 className="h-2.5 w-2.5 text-ok" aria-hidden />}
                                  {isFail && <XCircle className="h-2.5 w-2.5 text-halt" aria-hidden />}
                                  {!isSuccess && !isFail && <Clock className="h-2.5 w-2.5 text-idle" aria-hidden />}
                                </div>
                                {i < attempts.length - 1 && <div className="mt-1 w-px flex-1 bg-rail" />}
                              </div>

                              {/* Attempt content */}
                              <div className="flex-1 pb-4 min-w-0">
                                <div
                                  className={`border rounded-lg p-4 space-y-2 ${
                                    compareMode
                                      ? `cursor-pointer transition-all ${
                                          compareLeft === attempt.attemptNumber ? 'ring-2 ring-halt/50' :
                                          compareRight === attempt.attemptNumber ? 'ring-2 ring-ok/50' :
                                          'hover:ring-2 hover:ring-primary/30'
                                        }`
                                      : ''
                                  }`}
                                  onClick={compareMode ? () => {
                                    if (compareLeft === null) {
                                      setCompareLeft(attempt.attemptNumber);
                                    } else if (compareRight === null && attempt.attemptNumber !== compareLeft) {
                                      setCompareRight(attempt.attemptNumber);
                                    } else if (attempt.attemptNumber === compareLeft) {
                                      setCompareLeft(compareRight);
                                      setCompareRight(null);
                                    } else if (attempt.attemptNumber === compareRight) {
                                      setCompareRight(null);
                                    }
                                  } : undefined}
                                >
                                  <div className="flex items-center justify-between flex-wrap gap-2">
                                    <div className="flex items-center gap-2">
                                      {compareMode && (
                                        <span className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-bold uppercase ${
                                          compareLeft === attempt.attemptNumber ? 'bg-halt-soft text-halt' :
                                          compareRight === attempt.attemptNumber ? 'bg-ok-soft text-ok' :
                                          'bg-secondary text-muted-foreground'
                                        }`}>
                                          {compareLeft === attempt.attemptNumber ? 'A' : compareRight === attempt.attemptNumber ? 'B' : '·'}
                                        </span>
                                      )}
                                      <span className="font-semibold text-sm">
                                        {t('deliveryDetails.attemptNumber', { number: attempt.attemptNumber })}
                                      </span>
                                      {attempt.httpStatusCode && (
                                        <Badge variant={isSuccess ? 'ok' : 'halt'} className="px-1.5 py-0 font-mono text-[10px]">
                                          {attempt.httpStatusCode}
                                        </Badge>
                                      )}
                                      {attempt.durationMs != null && (
                                        <span className="text-xs text-muted-foreground flex items-center gap-0.5">
                                          <Timer className="h-3 w-3" />
                                          {attempt.durationMs}ms
                                          {prev?.durationMs != null && (
                                            attempt.durationMs > prev.durationMs
                                              ? <TrendingUp className="ml-0.5 h-3 w-3 text-halt" aria-hidden />
                                              : attempt.durationMs < prev.durationMs
                                                ? <TrendingDown className="ml-0.5 h-3 w-3 text-ok" aria-hidden />
                                                : null
                                          )}
                                        </span>
                                      )}
                                    </div>
                                    <span className="text-xs text-muted-foreground">
                                      {formatDateTime(attempt.createdAt)}
                                    </span>
                                  </div>

                                  {attempt.errorMessage && (
                                    <div className="mt-1">
                                      <p className="rounded bg-halt-soft p-2 font-mono text-xs text-halt">
                                        {attempt.errorMessage}
                                      </p>
                                    </div>
                                  )}

                                  {/* What was sent and what came back. Collapsed:
                                      four of these per attempt, and an attempt
                                      list is unreadable with them all open. */}
                                  <div className="mt-2 space-y-1">
                                    {attempt.requestHeaders && (
                                      <JsonBlock collapsible label={t('deliveryDetails.requestHeaders')} value={attempt.requestHeaders} maxHeight="max-h-32" />
                                    )}
                                    {attempt.requestBody && (
                                      <JsonBlock collapsible label={t('deliveryDetails.requestBody')} value={attempt.requestBody} maxHeight="max-h-48" />
                                    )}
                                    {attempt.responseHeaders && (
                                      <JsonBlock collapsible label={t('deliveryDetails.responseHeaders')} value={attempt.responseHeaders} maxHeight="max-h-32" />
                                    )}
                                    {attempt.responseBody && (
                                      <JsonBlock collapsible label={t('deliveryDetails.responseBody')} value={attempt.responseBody} maxHeight="max-h-48" />
                                    )}
                                  </div>
                                </div>
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </CardContent>
              </Card>

              {dryRunResult && (
                <Card className="border-rail bg-secondary/40">
                  <CardHeader className="pb-2">
                    <CardTitle className="text-sm flex items-center gap-2">
                      <Eye className="h-4 w-4 text-muted-foreground" aria-hidden />
                      {t('deliveryDetails.dryRun.title')}
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-2 text-sm">
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">{t('deliveryDetails.dryRun.plan')}</span>
                      <span className="font-mono text-xs max-w-[60%] text-right">{dryRunResult.plan}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">{t('deliveryDetails.dryRun.endpoint')}</span>
                      <span className="font-mono text-xs truncate max-w-[60%]">{dryRunResult.endpointUrl}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">{t('deliveryDetails.dryRun.idempotencyKey')}</span>
                      <span className="font-mono text-xs truncate max-w-[60%]">{dryRunResult.idempotencyKey}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">{t('deliveryDetails.dryRun.eventType')}</span>
                      <span className="font-mono text-xs">{dryRunResult.eventType}</span>
                    </div>
                    <Button variant="ghost" size="sm" onClick={() => setDryRunResult(null)} className="w-full mt-2">
                      {t('deliveryDetails.dryRun.dismiss')}
                    </Button>
                  </CardContent>
                </Card>
              )}

              <div className="flex gap-2 pt-4">
                <Button
                  variant="outline"
                  onClick={handleDryRun}
                  disabled={delivery.status === 'SUCCESS' || dryRunLoading}
                >
                  {dryRunLoading ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Eye className="mr-2 h-4 w-4" />}
                  {t('deliveryDetails.dryRun.button')}
                </Button>
                <Button
                  onClick={() => setShowReplayDialog(true)}
                  disabled={delivery.status === 'SUCCESS'}
                  className="flex-1"
                >
                  <RefreshCw className="mr-2 h-4 w-4" />
                  {t('deliveryDetails.replayDelivery')}
                </Button>
              </div>

              {delivery.attemptCount > 1 && delivery.status !== 'SUCCESS' && (
                <div className="pt-2">
                  <p className="text-xs text-muted-foreground mb-2">{t('deliveryDetails.replayFromStep.label')}</p>
                  <div className="flex gap-1 flex-wrap">
                    {Array.from({ length: delivery.attemptCount }, (_, i) => i + 1).map((step) => (
                      <Button
                        key={step}
                        variant={replayFromStep === step ? 'default' : 'outline'}
                        size="sm"
                        className="text-xs h-7 px-2"
                        onClick={() => {
                          setReplayFromStep(replayFromStep === step ? null : step);
                          setShowReplayDialog(true);
                        }}
                      >
                        <SkipForward className="mr-1 h-3 w-3" />
                        {t('deliveryDetails.replayFromStep.step', { number: step })}
                      </Button>
                    ))}
                  </div>
                </div>
              )}
            </div>
          ) : loadError ? (
            <ErrorState
              error={loadError}
              fallbackKey="deliveryDetails.toast.loadFailed"
              onRetry={loadDelivery}
              retrying={loading}
              className="flex flex-col items-center justify-center py-16"
            />
          ) : (
            <EmptyState
              icon={Info}
              title={t('deliveryDetails.noData')}
              className="flex flex-col items-center justify-center py-16"
            />
          )}
        </SheetContent>
      </Sheet>

      <AlertDialog open={showReplayDialog} onOpenChange={setShowReplayDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('deliveryDetails.replayDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>
              {replayFromStep !== null
                ? t('deliveryDetails.replayDialog.description') + ' ' + t('deliveryDetails.replayFromStep.step', { number: replayFromStep })
                : t('deliveryDetails.replayDialog.description')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={replaying}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleReplay} disabled={replaying}>
              {replaying && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              {replaying ? t('deliveryDetails.replaying') : t('deliveryDetails.replay')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
