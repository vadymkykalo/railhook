import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, RotateCcw, Send } from 'lucide-react';
import {
  usePortalAttempts, usePortalDeliveries, usePortalEndpoints, useRetryPortalDelivery,
} from '../../api/queries';
import type { PortalDeliveryFilters } from '../../api/portal.api';
import type { PortalDeliveryResponse } from '../../types/api.types';
import { showApiError, showSuccess } from '../../lib/toast';
import { formatDateTime } from '../../lib/date';
import AttemptRail from '../../components/AttemptRail';
import EmptyState, { ErrorState } from '../../components/EmptyState';
import JsonBlock from '../../components/JsonBlock';
import { SkeletonRows } from '../../components/PageSkeleton';
import StatusBadge, { kindOfDeliveryStatus } from '../../components/StatusBadge';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card } from '../../components/ui/card';
import { Select } from '../../components/ui/select';
import {
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
} from '../../components/ui/sheet';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../../components/ui/table';
import { TablePagination } from '../../components/ui/table-pagination';
import { FilterField } from '../tableParts';
import { railFromDeliveryAttempts } from '../attemptRailData';

const STATUSES: PortalDeliveryResponse['status'][] = ['SUCCESS', 'FAILED', 'DLQ', 'PENDING', 'PROCESSING'];

/** A Delivery the Railhook ladder has stopped on, which is the only kind a retry makes sense for. */
function retryable(status: PortalDeliveryResponse['status']): boolean {
  return status === 'FAILED' || status === 'DLQ';
}

export default function PortalDeliveries() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<PortalDeliveryFilters['status']>(undefined);
  const [endpointId, setEndpointId] = useState<string>('');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [openId, setOpenId] = useState<string | null>(null);

  const endpoints = usePortalEndpoints(true);
  const filters: PortalDeliveryFilters = { status, endpointId: endpointId || undefined, page, size: pageSize };
  const deliveries = usePortalDeliveries(filters, true);

  const urlOf = (id: string) => endpoints.data?.find((e) => e.id === id)?.url ?? id;
  const rows = deliveries.data?.content ?? [];
  const open = rows.find((d) => d.id === openId) ?? null;

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <FilterField id="portal-status" label={t('portal.deliveries.status')}>
          <Select
            id="portal-status" value={status ?? ''}
            onChange={(e) => { setStatus((e.target.value || undefined) as PortalDeliveryFilters['status']); setPage(0); }}
          >
            <option value="">{t('portal.deliveries.allStatuses')}</option>
            {STATUSES.map((s) => <option key={s} value={s}>{t(`deliveries.status.${s}`)}</option>)}
          </Select>
        </FilterField>
        <FilterField id="portal-endpoint" label={t('portal.deliveries.endpoint')} className="min-w-[14rem]">
          <Select id="portal-endpoint" value={endpointId} onChange={(e) => { setEndpointId(e.target.value); setPage(0); }}>
            <option value="">{t('portal.deliveries.allEndpoints')}</option>
            {(endpoints.data ?? []).map((e) => <option key={e.id} value={e.id}>{e.url}</option>)}
          </Select>
        </FilterField>
      </div>

      {deliveries.isLoading ? (
        <SkeletonRows count={4} height="h-12" />
      ) : deliveries.isError ? (
        <ErrorState error={deliveries.error} fallbackKey="portal.deliveries.loadFailed" onRetry={() => deliveries.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyState
          icon={Send}
          title={t('portal.deliveries.empty.title')}
          description={t('portal.deliveries.empty.description')}
        />
      ) : (
        <>
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('portal.deliveries.eventType')}</TableHead>
                  <TableHead>{t('portal.deliveries.endpoint')}</TableHead>
                  <TableHead>{t('portal.deliveries.status')}</TableHead>
                  <TableHead>{t('portal.deliveries.attempts')}</TableHead>
                  <TableHead>{t('portal.deliveries.created')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((delivery) => (
                  <TableRow
                    key={delivery.id}
                    className="cursor-pointer"
                    onClick={() => setOpenId(delivery.id)}
                    onKeyDown={(e) => { if (e.key === 'Enter') setOpenId(delivery.id); }}
                    tabIndex={0}
                    aria-label={t('portal.deliveries.open', { type: delivery.eventType ?? delivery.eventId })}
                  >
                    <TableCell className="font-mono text-[13px]">{delivery.eventType ?? '—'}</TableCell>
                    <TableCell className="max-w-[260px]">
                      <div className="truncate font-mono text-[12px] text-muted-foreground" title={urlOf(delivery.endpointId)}>
                        {urlOf(delivery.endpointId)}
                      </div>
                    </TableCell>
                    <TableCell>
                      <StatusBadge kind={kindOfDeliveryStatus(delivery.status)} label={t(`deliveries.status.${delivery.status}`)} />
                    </TableCell>
                    <TableCell className="font-mono text-[12px]">{delivery.attemptCount}/{delivery.maxAttempts}</TableCell>
                    <TableCell className="font-mono text-[11px] text-muted-foreground">{formatDateTime(delivery.createdAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
          {deliveries.data && (
            <TablePagination
              page={page}
              pageSize={pageSize}
              totalElements={deliveries.data.totalElements}
              totalPages={deliveries.data.totalPages}
              onPageChange={setPage}
              onPageSizeChange={(size) => { setPageSize(size); setPage(0); }}
            />
          )}
        </>
      )}

      <DeliverySheet delivery={open} endpointUrl={open ? urlOf(open.endpointId) : ''} onClose={() => setOpenId(null)} />
    </div>
  );
}

function DeliverySheet({
  delivery, endpointUrl, onClose,
}: {
  delivery: PortalDeliveryResponse | null;
  endpointUrl: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const attempts = usePortalAttempts(delivery?.id ?? null);
  const retry = useRetryPortalDelivery();

  const handleRetry = async () => {
    if (!delivery) return;
    try {
      await retry.mutateAsync(delivery.id);
      showSuccess(t('portal.deliveries.toast.retried'));
      onClose();
    } catch (err) {
      showApiError(err, 'portal.deliveries.toast.retryFailed');
    }
  };

  const rail = delivery && attempts.data ? railFromDeliveryAttempts(attempts.data, delivery) : null;

  return (
    <Sheet open={!!delivery} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
        {delivery && (
          <>
            <SheetHeader>
              <SheetTitle className="font-mono text-base">{delivery.eventType ?? delivery.eventId}</SheetTitle>
              <SheetDescription className="truncate font-mono text-xs">{endpointUrl}</SheetDescription>
            </SheetHeader>

            <div className="mt-4 flex flex-wrap items-center gap-3">
              <StatusBadge kind={kindOfDeliveryStatus(delivery.status)} label={t(`deliveries.status.${delivery.status}`)} />
              <span className="font-mono text-xs text-muted-foreground">{formatDateTime(delivery.createdAt)}</span>
              {retryable(delivery.status) && (
                <Button size="sm" className="ml-auto" onClick={handleRetry} disabled={retry.isPending}>
                  {retry.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
                  {t('portal.deliveries.retry')}
                </Button>
              )}
            </div>
            {retryable(delivery.status) && (
              <p className="mt-2 text-xs text-muted-foreground">{t('portal.deliveries.retryHint')}</p>
            )}

            {rail && rail.attempts.length > 0 && (
              <div className="mt-4">
                <AttemptRail
                  attempts={rail.attempts}
                  maxAttempts={rail.maxAttempts}
                  size="full"
                  ariaLabel={t('deliveries.rail.label', { count: delivery.attemptCount, total: delivery.maxAttempts })}
                />
              </div>
            )}

            <h3 className="mono-label mt-6">{t('portal.deliveries.attemptsTitle')}</h3>
            {attempts.isLoading ? (
              <SkeletonRows count={2} height="h-16" />
            ) : attempts.isError ? (
              <ErrorState error={attempts.error} fallbackKey="portal.deliveries.attemptsFailed" onRetry={() => attempts.refetch()} />
            ) : (attempts.data ?? []).length === 0 ? (
              <p className="mt-2 text-sm text-muted-foreground">{t('portal.deliveries.noAttempts')}</p>
            ) : (
              <ol className="mt-2 space-y-3">
                {(attempts.data ?? []).map((attempt) => {
                  const ok = attempt.httpStatusCode != null && attempt.httpStatusCode >= 200 && attempt.httpStatusCode < 300;
                  return (
                    <li key={attempt.id} className="border border-rail p-3">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-medium">
                            {t('deliveryDetails.attemptNumber', { number: attempt.attemptNumber })}
                          </span>
                          {attempt.httpStatusCode != null && (
                            <Badge variant={ok ? 'ok' : 'halt'} className="px-1.5 py-0 font-mono text-[10px]">
                              {attempt.httpStatusCode}
                            </Badge>
                          )}
                          {attempt.durationMs != null && (
                            <span className="font-mono text-xs text-muted-foreground">{attempt.durationMs}ms</span>
                          )}
                        </div>
                        <span className="text-xs text-muted-foreground">{formatDateTime(attempt.createdAt)}</span>
                      </div>
                      {attempt.errorMessage && (
                        <p className="mt-2 rounded bg-halt-soft p-2 font-mono text-xs text-halt">{attempt.errorMessage}</p>
                      )}
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
                    </li>
                  );
                })}
              </ol>
            )}
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
