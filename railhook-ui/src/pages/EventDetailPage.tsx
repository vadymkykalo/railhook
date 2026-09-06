import { useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
  Copy, Send, Share2, Terminal, FileJson, Shield,
  FileType, Loader2, ExternalLink,
} from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { useEvent, useEventTypes } from '../api/queries';
import { deliveriesApi } from '../api/deliveries.api';
import { debugLinksApi } from '../api/debugLinks.api';
import { useQuery } from '@tanstack/react-query';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import { formatJson } from '../lib/json';
import { showSuccess, showApiError } from '../lib/toast';
import PageSkeleton, { SkeletonTable } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge, { kindOfDeliveryStatus } from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { usePermissions } from '../auth/usePermissions';
import type { DeliveryResponse, PageResponse } from '../types/api.types';
import { railFromCounts } from './attemptRailData';
import { AttemptCell, CopyId, TimeCell } from './tableParts';


export default function EventDetailPage() {
  const { t } = useTranslation();
  const { projectId, eventId } = useParams<{ projectId: string; eventId: string }>();
  const navigate = useNavigate();
  const { canManageEndpoints } = usePermissions();

  const [activeTab, setActiveTab] = useState<'raw' | 'sanitized' | 'schema' | 'deliveries' | 'debug'>('raw');
  const [sharingDebug, setSharingDebug] = useState(false);

  const {
    data: event, isLoading, isError, error, refetch, isRefetching,
  } = useEvent(projectId, eventId);
  const { data: eventTypes } = useEventTypes(projectId);

  const { data: deliveriesData, isLoading: deliveriesLoading, refetch: refetchDeliveries } = useQuery({
    // Prefixed with 'deliveries' so a replay's invalidation reaches this list too.
    queryKey: ['deliveries', projectId, eventId, 'event-detail'],
    queryFn: () => deliveriesApi.listByProject(projectId!, { eventId, size: 50 }),
    enabled: !!projectId && !!eventId,
  });

  const { data: debugLinks = [], refetch: refetchLinks } = useQuery({
    queryKey: ['debug-links', projectId, eventId],
    queryFn: () => debugLinksApi.listForEvent(projectId!, eventId!),
    enabled: !!projectId && !!eventId,
  });

  const deliveries: DeliveryResponse[] = (deliveriesData as PageResponse<DeliveryResponse>)?.content ?? [];

  const handleCopy = (text: string, copiedMessage: string) => {
    navigator.clipboard.writeText(text);
    showSuccess(copiedMessage);
  };

  const formatPayload = (payload: string | undefined) => (payload ? formatJson(payload) : '');

  const generateCurl = () => {
    if (!event) return '';
    return `curl -X POST \\\n  -H "Content-Type: application/json" \\\n  -H "X-API-Key: YOUR_API_KEY" \\\n  -d '${event.payload || '{}'}' \\\n  "https://your-api.com/api/v1/events"`;
  };

  const handleShareDebug = async () => {
    if (!projectId || !eventId) return;
    setSharingDebug(true);
    try {
      const link = await debugLinksApi.create(projectId, eventId, { expiryHours: 24 });
      await navigator.clipboard.writeText(link.shareUrl);
      showSuccess(t('eventDetail.debugLinkCreated'));
      refetchLinks();
    } catch (err: any) {
      showApiError(err, 'eventDetail.debugLinkFailed');
    } finally {
      setSharingDebug(false);
    }
  };

  const handleReplayUndelivered = async () => {
    const undelivered = deliveries.filter(d => d.status === 'FAILED' || d.status === 'DLQ');
    for (const d of undelivered) {
      try { await deliveriesApi.replay(d.id); } catch { /* keep going: one failure must not stop the rest */ }
    }
    showSuccess(t('eventDetail.replayedFailed', { count: undelivered.length }));
    refetchDeliveries();
  };

  const matchingSchema = eventTypes?.find((et) => et.name === event?.eventType);

  if (isLoading) return <PageSkeleton maxWidth="max-w-none" />;
  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      </div>
    );
  }
  if (!event) {
    return (
      <div className="p-4 lg:p-6">
        <EmptyState icon={FileJson} title={t('events.details.notFound')} />
      </div>
    );
  }

  const undeliveredCount = deliveries.filter(d => d.status === 'FAILED' || d.status === 'DLQ').length;

  const tabs = [
    { id: 'raw' as const, label: t('eventDetail.tabs.raw'), icon: FileJson },
    { id: 'sanitized' as const, label: t('eventDetail.tabs.sanitized'), icon: Shield },
    { id: 'schema' as const, label: t('eventDetail.tabs.schema'), icon: FileType },
    { id: 'deliveries' as const, label: t('eventDetail.tabs.deliveries'), icon: Send, badge: deliveries.length },
    { id: 'debug' as const, label: t('eventDetail.tabs.debug'), icon: Share2, badge: debugLinks.length },
  ];

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={
          <span className="flex items-center gap-2">
            {t('events.eventId')}
            <span className="normal-case tracking-normal text-foreground">{event.id}</span>
            <Button
              variant="ghost"
              size="icon-sm"
              className="h-5 w-5"
              onClick={() => handleCopy(event.id, t('eventDetail.eventIdCopied'))}
              title={t('common.copyId')}
              aria-label={t('common.copyId')}
            >
              <Copy className="h-3 w-3" />
            </Button>
          </span>
        }
        title={event.eventType}
        description={`${formatDateTime(event.createdAt)} · ${formatRelativeTime(event.createdAt)}`}
        actions={
          <>
            <Button variant="outline" onClick={() => handleCopy(generateCurl(), t('eventDetail.curlCopied'))}>
              <Terminal className="h-4 w-4" /> {t('eventDetail.copyCurl')}
            </Button>
            <Button variant="outline" onClick={handleShareDebug} disabled={sharingDebug}>
              {sharingDebug ? <Loader2 className="h-4 w-4 animate-spin" /> : <Share2 className="h-4 w-4" />}
              {t('eventDetail.share')}
            </Button>
            {undeliveredCount > 0 && canManageEndpoints && (
              <Button onClick={handleReplayUndelivered}>
                <Send className="h-4 w-4" /> {t('eventDetail.replayFailed', { count: undeliveredCount })}
              </Button>
            )}
          </>
        }
      />

      <dl className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {[
          { label: t('eventDetail.eventType'), value: event.eventType },
          { label: t('eventDetail.deliveriesCount'), value: String(event.deliveriesCreated ?? deliveries.length) },
          { label: t('eventDetail.payloadSize'), value: event.payload ? `${(event.payload.length / 1024).toFixed(1)} KB` : '—' },
          { label: t('eventDetail.project'), value: event.projectId.substring(0, 8) },
        ].map((metric) => (
          <div key={metric.label} className="rounded-lg border border-rail bg-card px-4 py-3">
            <dt className="mono-label">{metric.label}</dt>
            <dd className="mt-1 truncate font-mono text-[15px]" title={metric.value}>{metric.value}</dd>
          </div>
        ))}
      </dl>

      <div className="border-b border-rail">
        <div role="tablist" className="flex gap-1 overflow-x-auto">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              role="tab"
              aria-selected={activeTab === tab.id}
              className={`flex items-center gap-1.5 whitespace-nowrap border-b-2 px-2.5 py-2.5 text-[13px] transition-colors ${
                activeTab === tab.id
                  ? 'border-primary font-medium text-foreground'
                  : 'border-transparent text-muted-foreground hover:border-rail hover:text-foreground'
              }`}
              onClick={() => setActiveTab(tab.id)}
            >
              <tab.icon className="h-3.5 w-3.5" aria-hidden />
              {tab.label}
              {tab.badge !== undefined && tab.badge > 0 && (
                <span className="ml-1 rounded-full bg-secondary px-1.5 py-0.5 font-mono text-[10px]">{tab.badge}</span>
              )}
            </button>
          ))}
        </div>
      </div>

      <div className="animate-fade-in pt-5">
        {activeTab === 'raw' && (
          <section className="overflow-hidden rounded-lg border border-rail bg-card">
            <div className="flex items-center justify-between border-b border-rail px-4 py-2.5">
              <h3 className="text-[13px] font-medium">{t('eventDetail.rawPayload')}</h3>
              <Button variant="ghost" size="sm" onClick={() => handleCopy(formatPayload(event.payload), t('eventDetail.payloadCopied'))}>
                <Copy className="h-3.5 w-3.5" /> {t('common.copy')}
              </Button>
            </div>
            <pre className="max-h-[60vh] overflow-auto whitespace-pre-wrap break-words p-4 font-mono text-xs">
              {formatPayload(event.payload) || <span className="italic text-muted-foreground">{t('events.details.noPayload')}</span>}
            </pre>
          </section>
        )}

        {activeTab === 'sanitized' && (
          <section className="rounded-lg border border-rail bg-card p-4">
            <h3 className="flex items-center gap-2 text-[13px] font-medium">
              <Shield className="h-4 w-4 text-muted-foreground" aria-hidden />
              {t('eventDetail.sanitized')}
            </h3>
            <p className="mt-1 text-xs text-muted-foreground">{t('eventDetail.sanitizedHint')}</p>
            <p className="py-8 text-center text-sm text-muted-foreground">{t('eventDetail.sanitizedUseDebug')}</p>
            {debugLinks.length > 0 && (
              <div className="border-t border-rail pt-4">
                <p className="mono-label mb-2">{t('eventDetail.existingLinks')}</p>
                {debugLinks.map((link) => (
                  <div key={link.id} className="flex items-center gap-2 py-1">
                    <a href={link.shareUrl} target="_blank" rel="noopener noreferrer" className="flex-1 truncate font-mono text-xs text-primary hover:underline">
                      {link.shareUrl}
                    </a>
                    <Button variant="ghost" size="icon-sm" onClick={() => handleCopy(link.shareUrl, t('eventDetail.linkCopied'))} title={t('eventDetail.copyLink')} aria-label={t('eventDetail.copyLink')}>
                      <Copy className="h-3 w-3" />
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </section>
        )}

        {activeTab === 'schema' && (
          <section className="rounded-lg border border-rail bg-card p-4">
            <h3 className="text-[13px] font-medium">{t('eventDetail.schemaInfo')}</h3>
            {matchingSchema ? (
              <div className="mt-3 space-y-3">
                <div className="flex items-center gap-2 font-mono text-[13px]">
                  <span>{matchingSchema.name}</span>
                  {matchingSchema.latestVersion && <span className="text-muted-foreground">v{matchingSchema.latestVersion}</span>}
                </div>
                {matchingSchema.description && <p className="text-sm text-muted-foreground">{matchingSchema.description}</p>}
                <Button variant="outline" size="sm" onClick={() => navigate(`/admin/projects/${projectId}/schemas`)}>
                  <ExternalLink className="h-3.5 w-3.5" /> {t('eventDetail.viewSchemaRegistry')}
                </Button>
              </div>
            ) : (
              <EmptyState
                icon={FileType}
                title={t('eventDetail.noSchema', { type: event.eventType })}
                className="flex flex-col items-center justify-center py-8"
              />
            )}
          </section>
        )}

        {activeTab === 'deliveries' && (
          <section className="overflow-hidden rounded-lg border border-rail bg-card">
            {deliveriesLoading ? (
              <SkeletonTable rows={4} />
            ) : deliveries.length === 0 ? (
              <EmptyState
                icon={Send}
                title={t('events.details.noDeliveries')}
                description={(
                  <Trans i18nKey="events.details.noDeliveriesNoSub" values={{ eventType: event.eventType }} components={{ strong: <strong /> }} />
                )}
                action={(
                  <Button variant="outline" size="sm" onClick={() => navigate(`/admin/projects/${projectId}/subscriptions`)}>
                    {t('deliveries.noDeliveriesForEventAction')}
                  </Button>
                )}
                className="flex flex-col items-center justify-center py-10"
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('deliveries.columns.status')}</TableHead>
                    <TableHead>{t('deliveries.columns.endpoint')}</TableHead>
                    <TableHead>{t('deliveries.columns.attempts')}</TableHead>
                    <TableHead>{t('deliveries.columns.created')}</TableHead>
                    <TableHead>{t('deliveries.columns.deliveryId')}</TableHead>
                    <TableHead className="w-[60px]"><span className="sr-only">{t('common.actions')}</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {deliveries.map((d) => {
                    const rail = railFromCounts(d.attemptCount, d.maxAttempts, d.status);
                    return (
                      <TableRow key={d.id} className="group/row">
                        <TableCell>
                          <StatusBadge kind={kindOfDeliveryStatus(d.status)} label={t(`deliveries.status.${d.status}`)} />
                        </TableCell>
                        <TableCell>
                          <Link
                            to={`/admin/projects/${projectId}/endpoints`}
                            className="block max-w-[220px] truncate font-mono text-[13px] underline-offset-4 hover:underline"
                          >
                            {d.endpointId.substring(0, 8)}
                          </Link>
                        </TableCell>
                        <TableCell>
                          <AttemptCell
                            rail={rail.attempts}
                            maxAttempts={rail.maxAttempts}
                            attemptCount={d.attemptCount}
                            ladderLength={d.maxAttempts}
                            nextRetryAt={d.status === 'PENDING' ? d.nextRetryAt : undefined}
                          />
                        </TableCell>
                        <TableCell><TimeCell value={d.createdAt} /></TableCell>
                        <TableCell><CopyId value={d.id} /></TableCell>
                        <TableCell>
                          {(d.status === 'FAILED' || d.status === 'DLQ') && canManageEndpoints && (
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              onClick={() => deliveriesApi.replay(d.id).then(() => { showSuccess(t('eventDetail.replayed')); refetchDeliveries(); })}
                              title={t('events.details.replay')}
                              aria-label={t('events.details.replay')}
                            >
                              <Send className="h-3.5 w-3.5" />
                            </Button>
                          )}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            )}
          </section>
        )}

        {activeTab === 'debug' && (
          <section className="rounded-lg border border-rail bg-card">
            <div className="flex items-center justify-between border-b border-rail px-4 py-2.5">
              <h3 className="text-[13px] font-medium">{t('eventDetail.tabs.debug')}</h3>
              <Button size="sm" onClick={handleShareDebug} disabled={sharingDebug}>
                {sharingDebug ? <Loader2 className="h-4 w-4 animate-spin" /> : <Share2 className="h-4 w-4" />}
                {t('eventDetail.createLink')}
              </Button>
            </div>
            <div className="p-4">
              {debugLinks.length === 0 ? (
                <EmptyState
                  icon={Share2}
                  title={t('eventDetail.noDebugLinks')}
                  className="flex flex-col items-center justify-center py-8"
                />
              ) : (
                <div className="space-y-3">
                  {debugLinks.map((link) => (
                    <div key={link.id} className="flex items-center justify-between gap-3 rounded-lg border border-rail p-3">
                      <div className="min-w-0 flex-1">
                        <a href={link.shareUrl} target="_blank" rel="noopener noreferrer" className="block truncate font-mono text-sm text-primary hover:underline">
                          {link.shareUrl}
                        </a>
                        <div className="mt-1 flex items-center gap-3 text-[11px] text-muted-foreground">
                          <span>{t('eventDetail.views', { count: link.viewCount })}</span>
                          <span>{t('eventDetail.expires', { time: formatRelativeTime(link.expiresAt) })}</span>
                        </div>
                      </div>
                      <Button variant="ghost" size="icon-sm" onClick={() => handleCopy(link.shareUrl, t('eventDetail.debugLinkCopied'))} title={t('eventDetail.copyLink')} aria-label={t('eventDetail.copyLink')}>
                        <Copy className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </section>
        )}
      </div>
    </div>
  );
}
