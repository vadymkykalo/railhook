import { useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { Copy, Radio, Send, FileJson, Shield, ExternalLink } from 'lucide-react';
import { useEvent } from '../api/queries';
import { useQuery } from '@tanstack/react-query';
import { deliveriesApi } from '../api/deliveries.api';
import { debugLinksApi } from '../api/debugLinks.api';
import { formatDateTime } from '../lib/date';
import { showSuccess, showApiError } from '../lib/toast';
import { railFromCounts } from '../pages/attemptRailData';
import AttemptRail from './AttemptRail';
import StatusBadge, { kindOfDeliveryStatus } from './StatusBadge';
import EmptyState, { ErrorState } from './EmptyState';
import { SkeletonRows } from './PageSkeleton';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from './ui/sheet';
import { Button } from './ui/button';
import { formatJson } from '../lib/json';

interface EventDetailsSheetProps {
  projectId: string;
  eventId: string | null;
  onClose: () => void;
  onViewDeliveries?: (eventId: string) => void;
}

/**
 * The quick look at one Event: what was announced, and what became of the
 * Deliveries it created. Each delivery carries its own attempt rail, so the
 * answer to "is anything still owed" is visible without opening a second view.
 */
export default function EventDetailsSheet({
  projectId,
  eventId,
  onClose,
  onViewDeliveries,
}: EventDetailsSheetProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const {
    data: event, isLoading, isError, error, refetch, isRefetching,
  } = useEvent(projectId, eventId ?? undefined);
  const [activeTab, setActiveTab] = useState<'raw' | 'sanitized' | 'deliveries'>('raw');

  const { data: deliveriesData, isLoading: deliveriesLoading, refetch: refetchDeliveries } = useQuery({
    queryKey: ['deliveries', projectId, eventId, 'sheet'],
    queryFn: () => deliveriesApi.listByProject(projectId, { eventId: eventId!, size: 50 }),
    enabled: !!eventId && activeTab === 'deliveries',
  });
  const deliveries = deliveriesData?.content ?? [];

  const [sanitizedPayload, setSanitizedPayload] = useState<string | null>(null);
  const [sanitizedLoading, setSanitizedLoading] = useState(false);

  const loadSanitized = async () => {
    if (!projectId || !eventId || sanitizedPayload) return;
    setSanitizedLoading(true);
    try {
      const link = await debugLinksApi.create(projectId, eventId, { expiryHours: 1 });
      const pub = await debugLinksApi.viewPublic(link.token);
      setSanitizedPayload(pub.sanitizedPayload);
    } catch {
      setSanitizedPayload(null);
    } finally {
      setSanitizedLoading(false);
    }
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    showSuccess(t('common.copied'));
  };

  const formatPayload = (payload: string | undefined) => (payload ? formatJson(payload) : '');

  const tabs = [
    { id: 'raw' as const, label: t('events.details.raw'), icon: FileJson },
    { id: 'sanitized' as const, label: t('events.details.sanitized'), icon: Shield },
    { id: 'deliveries' as const, label: t('events.details.deliveries'), icon: Send },
  ];

  return (
    <Sheet open={!!eventId} onOpenChange={(open) => !open && onClose()}>
      <SheetContent side="right" className="w-full overflow-y-auto sm:max-w-2xl">
        <SheetHeader className="border-b border-rail pb-4">
          <SheetTitle className="flex items-center gap-2">
            <Radio className="h-4 w-4 text-muted-foreground" aria-hidden />
            {t('events.details.title')}
          </SheetTitle>
          <SheetDescription className="font-mono">{event?.eventType}</SheetDescription>
        </SheetHeader>

        {isLoading ? (
          <div className="space-y-4 pt-6">
            <div className="h-4 w-48 animate-pulse rounded bg-muted" />
            <div className="h-32 animate-pulse rounded bg-muted" />
            <div className="h-4 w-32 animate-pulse rounded bg-muted" />
          </div>
        ) : event ? (
          <div className="space-y-6 pt-4">
            <dl className="grid grid-cols-2 gap-4">
              <div className="min-w-0">
                <dt className="mono-label">{t('events.eventId')}</dt>
                <dd className="mt-1 flex items-center gap-1.5">
                  <code className="break-all font-mono text-xs">{event.id}</code>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    className="h-5 w-5 flex-shrink-0"
                    onClick={() => handleCopy(event.id)}
                    title={t('common.copyId')}
                    aria-label={t('common.copyId')}
                  >
                    <Copy className="h-3 w-3" />
                  </Button>
                </dd>
              </div>
              <div>
                <dt className="mono-label">{t('events.created')}</dt>
                <dd className="mt-1 font-mono text-xs">{formatDateTime(event.createdAt)}</dd>
              </div>
              <div>
                <dt className="mono-label">{t('events.eventType')}</dt>
                <dd className="mt-1 font-mono text-xs">{event.eventType}</dd>
              </div>
              {event.deliveriesCreated !== undefined && (
                <div>
                  <dt className="mono-label">{t('events.details.deliveries')}</dt>
                  <dd className="mt-1 font-mono text-xs">{event.deliveriesCreated}</dd>
                </div>
              )}
            </dl>

            <div className="border-b border-rail">
              <div role="tablist" className="flex gap-1">
                {tabs.map((tab) => (
                  <button
                    key={tab.id}
                    role="tab"
                    aria-selected={activeTab === tab.id}
                    className={`flex items-center gap-1.5 border-b-2 px-2.5 py-2 text-[13px] transition-colors ${
                      activeTab === tab.id
                        ? 'border-primary font-medium text-foreground'
                        : 'border-transparent text-muted-foreground hover:border-rail hover:text-foreground'
                    }`}
                    onClick={() => {
                      setActiveTab(tab.id);
                      if (tab.id === 'sanitized') loadSanitized();
                    }}
                  >
                    <tab.icon className="h-3.5 w-3.5" aria-hidden />
                    {tab.label}
                  </button>
                ))}
              </div>
            </div>

            {activeTab === 'raw' && (
              <div className="relative">
                <Button variant="ghost" size="sm" className="absolute right-2 top-2 z-10" onClick={() => handleCopy(formatPayload(event.payload))}>
                  <Copy className="h-3 w-3" /> {t('common.copy')}
                </Button>
                <pre className="max-h-[50vh] overflow-auto whitespace-pre-wrap break-words rounded-lg border border-rail p-4 font-mono text-xs">
                  {formatPayload(event.payload) || <span className="italic text-muted-foreground">{t('events.details.noPayload')}</span>}
                </pre>
              </div>
            )}

            {activeTab === 'sanitized' && (
              <div className="relative">
                {sanitizedLoading ? (
                  <SkeletonRows count={1} height="h-40" />
                ) : sanitizedPayload ? (
                  <>
                    <Button variant="ghost" size="sm" className="absolute right-2 top-2 z-10" onClick={() => handleCopy(formatPayload(sanitizedPayload))}>
                      <Copy className="h-3 w-3" /> {t('common.copy')}
                    </Button>
                    <pre className="max-h-[50vh] overflow-auto whitespace-pre-wrap break-words rounded-lg border border-rail p-4 font-mono text-xs">
                      {formatPayload(sanitizedPayload)}
                    </pre>
                  </>
                ) : (
                  <EmptyState
                    icon={Shield}
                    title={t('events.details.sanitizedUnavailable')}
                    className="flex flex-col items-center justify-center py-8"
                  />
                )}
              </div>
            )}

            {activeTab === 'deliveries' && (
              <div className="space-y-2">
                {deliveriesLoading ? (
                  <SkeletonRows count={3} height="h-14" />
                ) : deliveries.length === 0 ? (
                  <EmptyState
                    icon={Send}
                    title={t('events.details.noDeliveries')}
                    description={event.eventType
                      ? <Trans i18nKey="events.details.noDeliveriesNoSub" values={{ eventType: event.eventType }} components={{ strong: <strong /> }} />
                      : t('events.details.noDeliveriesHint')}
                    className="flex flex-col items-center justify-center py-8"
                  />
                ) : (
                  deliveries.map((d) => {
                    const rail = railFromCounts(d.attemptCount, d.maxAttempts, d.status);
                    return (
                      <div key={d.id} className="flex items-center justify-between gap-3 rounded-lg border border-rail px-3 py-2.5">
                        <div className="min-w-0 space-y-1.5">
                          <div className="flex items-center gap-2">
                            <StatusBadge kind={kindOfDeliveryStatus(d.status)} label={t(`deliveries.status.${d.status}`)} />
                            <code className="font-mono text-[11px] text-muted-foreground">{d.endpointId.substring(0, 8)}</code>
                          </div>
                          <AttemptRail
                            attempts={rail.attempts}
                            maxAttempts={rail.maxAttempts}
                            size="inline"
                            ariaLabel={t('deliveries.rail.label', { count: d.attemptCount, total: d.maxAttempts })}
                          />
                          <span className="block font-mono text-[10px] text-muted-foreground">
                            {d.attemptCount}/{d.maxAttempts}
                          </span>
                        </div>
                        {(d.status === 'FAILED' || d.status === 'DLQ') && (
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => deliveriesApi.replay(d.id)
                              .then(() => { showSuccess(t('eventDetail.replayed')); refetchDeliveries(); })
                              .catch(e => showApiError(e, 'deliveries.toast.replayFailed'))}
                          >
                            {t('events.details.replay')}
                          </Button>
                        )}
                      </div>
                    );
                  })
                )}
              </div>
            )}

            <div className="flex gap-2 border-t border-rail pt-4">
              <Button
                variant="outline"
                size="sm"
                className="flex-1"
                onClick={() => { onClose(); navigate(`/admin/projects/${projectId}/events/${event.id}`); }}
              >
                <ExternalLink className="h-3.5 w-3.5" />
                {t('events.details.openFull')}
              </Button>
              {onViewDeliveries && (
                <Button variant="outline" size="sm" className="flex-1" onClick={() => onViewDeliveries(event.id)}>
                  <Send className="h-3.5 w-3.5" />
                  {t('events.viewDeliveries')}
                </Button>
              )}
            </div>
          </div>
        ) : isError ? (
          <ErrorState
            error={error}
            onRetry={() => refetch()}
            retrying={isRefetching}
            className="flex flex-col items-center justify-center pt-6"
          />
        ) : (
          <EmptyState
            icon={Radio}
            title={t('events.details.notFound')}
            className="flex flex-col items-center justify-center pt-6"
          />
        )}
      </SheetContent>
    </Sheet>
  );
}
