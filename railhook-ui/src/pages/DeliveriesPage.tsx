import { useState, useEffect, useMemo } from 'react';
import { useParams, useSearchParams, useNavigate, Link } from 'react-router-dom';
import { Send, RefreshCw, AlertTriangle, Loader2, Radio, X } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showWarning } from '../lib/toast';
import { formatRelativeFuture } from '../lib/date';
import { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge, { kindOfDeliveryStatus } from '../components/StatusBadge';
import { useProject, useEndpoints, useDeliveries, useEvent, useBulkReplayDeliveries } from '../api/queries';
import type { DeliveryResponse } from '../types/api.types';
import { Button } from '../components/ui/button';
import { Select } from '../components/ui/select';
import { SortableTableHead, useSort } from '../components/ui/sortable-table-head';
import { TablePagination } from '../components/ui/table-pagination';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../components/ui/table';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import DeliveryDetailsSheet from './DeliveryDetailsSheet';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { railFromCounts } from './attemptRailData';
import { AttemptCell, CopyId, FilterBar, FilterField, SearchField, SelectBox, SelectionBar, SORTABLE_HEAD_CLASS, TimeCell } from './tableParts';
import { useDebounced } from '../hooks/useDebounced';
import Callout from '../components/Callout';
import { cn } from '../lib/utils';

const STATUS_VALUES = ['', 'SUCCESS', 'FAILED', 'DLQ', 'PENDING', 'PROCESSING'] as const;
const DATE_RANGE_VALUES = ['24h', '7d', '30d'] as const;

function statusOptions(t: (key: string) => string) {
  return STATUS_VALUES.map((value) => ({
    value,
    label: value === '' ? t('deliveries.filters.allStatuses') : t(`deliveries.status.${value}`),
  }));
}

function dateRangeOptions(t: (key: string) => string) {
  const labelKeys: Record<(typeof DATE_RANGE_VALUES)[number], string> = {
    '24h': 'deliveries.filters.last24h',
    '7d': 'deliveries.filters.last7d',
    '30d': 'deliveries.filters.last30d',
  };
  return DATE_RANGE_VALUES.map((value) => ({ value, label: t(labelKeys[value]) }));
}

/** Trailing window bound to a coarse (minute) grain so the upper bound keeps
 * advancing as time passes without changing — and re-fetching — on every render. */
function dateRangeBounds(dateRange: string, nowMinute: number): { fromDate?: string; toDate?: string } {
  const now = nowMinute * 60_000;
  const toDate = new Date(now).toISOString();
  const spanMs: Record<string, number> = {
    '24h': 24 * 60 * 60 * 1000,
    '7d': 7 * 24 * 60 * 60 * 1000,
    '30d': 30 * 24 * 60 * 60 * 1000,
  };
  const span = spanMs[dateRange];
  if (!span) return {};
  return { fromDate: new Date(now - span).toISOString(), toDate };
}

/** What the status badge says under itself: why this delivery is where it is. */
function explainOf(delivery: DeliveryResponse): { key: string; values?: Record<string, string | number> } | null {
  if (delivery.status === 'PENDING' && delivery.attemptCount > 0 && delivery.nextRetryAt) {
    return { key: 'deliveries.statusExplain.PENDING_RETRY', values: { time: formatRelativeFuture(delivery.nextRetryAt) } };
  }
  if (delivery.status === 'PENDING') return { key: 'deliveries.statusExplain.PENDING_NEW' };
  if (delivery.status === 'PROCESSING') return { key: 'deliveries.statusExplain.PROCESSING' };
  if (delivery.status === 'DLQ') return { key: 'deliveries.statusExplain.DLQ', values: { count: delivery.attemptCount } };
  if (delivery.status === 'FAILED') return { key: 'deliveries.statusExplain.FAILED' };
  return null;
}

/** A delivered obligation has nothing left to replay. */
const isReplayable = (d: DeliveryResponse) => d.status !== 'SUCCESS';

export default function DeliveriesPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const eventIdFilter = searchParams.get('eventId') || '';

  const [statusFilter, setStatusFilter] = useState('');
  const [endpointFilter, setEndpointFilter] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [dateRange, setDateRange] = useState('24h');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const { sort, toggle: toggleSort, param: sortParam } = useSort('createdAt', 'desc');

  const [selectedDeliveryId, setSelectedDeliveryId] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [showBulkReplayDialog, setShowBulkReplayDialog] = useState(false);
  const { canReplayDeliveries } = usePermissions();

  const debouncedSearch = useDebounced(searchQuery);

  useEffect(() => setPage(0), [debouncedSearch]);

  // Advances once a minute so the trailing date-range window keeps including
  // newly-created deliveries without recomputing (and re-fetching) every render.
  const [nowMinute, setNowMinute] = useState(() => Math.floor(Date.now() / 60_000));
  useEffect(() => {
    const id = setInterval(() => setNowMinute(Math.floor(Date.now() / 60_000)), 60_000);
    return () => clearInterval(id);
  }, []);

  const { data: project, isLoading: projectLoading, isError: projectIsError, error: projectError, refetch: refetchProject } = useProject(projectId);
  const { data: endpoints = [] } = useEndpoints(projectId);
  const { data: filteredEvent } = useEvent(projectId, eventIdFilter || undefined);
  const filteredEventType = filteredEvent?.eventType ?? null;

  const { fromDate, toDate } = dateRangeBounds(dateRange, nowMinute);
  const {
    data: deliveriesData, isLoading: deliveriesLoading, isError: deliveriesIsError, error: deliveriesError, refetch: refetchDeliveries,
  } = useDeliveries(projectId, {
    page,
    size: pageSize,
    sort: sortParam,
    status: statusFilter || undefined,
    endpointId: endpointFilter || undefined,
    eventId: eventIdFilter || undefined,
    eventType: debouncedSearch || undefined,
    fromDate: eventIdFilter ? undefined : fromDate,
    toDate: eventIdFilter ? undefined : toDate,
  });
  const deliveries = useMemo(() => deliveriesData?.content ?? [], [deliveriesData]);
  const totalElements = deliveriesData?.totalElements ?? 0;
  const totalPages = deliveriesData?.totalPages ?? 0;

  const loading = projectLoading || deliveriesLoading;
  const isError = projectIsError || deliveriesIsError;
  const retry = () => { refetchProject(); refetchDeliveries(); };

  const bulkReplayMutation = useBulkReplayDeliveries();
  const bulkReplaying = bulkReplayMutation.isPending;

  const selectable = useMemo(() => deliveries.filter(isReplayable), [deliveries]);
  const selectedCount = selectedIds.size;
  const allSelected = selectable.length > 0 && selectable.every((d) => selectedIds.has(d.id));

  const toggleRow = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    setSelectedIds(allSelected ? new Set() : new Set(selectable.map((d) => d.id)));
  };

  const getEndpointName = (endpointId: string) => {
    const endpoint = endpoints.find(e => e.id === endpointId);
    return endpoint?.url ?? endpointId.substring(0, 8);
  };

  const handleReplaySelected = async () => {
    if (!projectId || selectedCount === 0) return;
    try {
      const response = await bulkReplayMutation.mutateAsync({ deliveryIds: Array.from(selectedIds), projectId });
      setSelectedIds(new Set());
      if (response.skipped > 0) {
        showWarning(t('deliveries.replayedSomeSkipped', { replayed: response.replayed, skipped: response.skipped }));
      } else {
        showSuccess(t('deliveries.replayedCount', { count: response.replayed }));
      }
    } catch (err: any) {
      showApiError(err, 'deliveries.toast.replayFailed');
    }
  };

  const handleReplayMatching = async () => {
    if (!projectId) return;
    try {
      const response = await bulkReplayMutation.mutateAsync({
        projectId,
        status: statusFilter || undefined,
        endpointId: endpointFilter || undefined,
      });
      if (response.hasMore) {
        showWarning(t('deliveries.bulkReplayHasMore', { replayed: response.replayed, total: response.totalMatched }));
      } else {
        showSuccess(t('deliveries.replayedCount', { count: response.replayed }));
      }
    } catch (err: any) {
      showApiError(err, 'deliveries.toast.replayFailed');
    }
  };

  if (loading) {
    return (
      <div className="p-4 lg:p-6">
        <SkeletonRows count={5} />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={projectError ?? deliveriesError} fallbackKey="deliveries.toast.loadFailed" onRetry={retry} />
      </div>
    );
  }

  const replayAllMatching = (statusFilter === 'FAILED' || statusFilter === 'DLQ') && totalElements > 0 && (
    <PermissionGate allowed={canReplayDeliveries}>
      <VerificationGate>
        <Button onClick={() => setShowBulkReplayDialog(true)} disabled={bulkReplaying} variant="outline">
          {bulkReplaying && <RefreshCw className="h-3.5 w-3.5 animate-spin" />}
          {t('deliveries.replayMatching', { count: totalElements })}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('nav.outgoing')}
        description={<Trans i18nKey="deliveries.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
        actions={replayAllMatching || undefined}
      />

      {eventIdFilter && (
        <div className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-rail bg-secondary/50 px-3 py-2">
          <Send className="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" aria-hidden />
          <span className="text-sm text-muted-foreground">{t('deliveries.filteringByEvent')}</span>
          <code className="font-mono text-[13px]">{eventIdFilter.substring(0, 8)}</code>
          <Button variant="ghost" size="sm" className="ml-auto" onClick={() => setSearchParams({})}>
            <X className="h-3.5 w-3.5" />
            {t('deliveries.clearEventFilter')}
          </Button>
        </div>
      )}

      <FilterBar>
        <FilterField id="status" label={t('deliveries.filters.status')}>
          <Select id="status" value={statusFilter} onChange={(e) => { setStatusFilter(e.target.value); setPage(0); }}>
            {statusOptions(t).map(opt => (<option key={opt.value} value={opt.value}>{opt.label}</option>))}
          </Select>
        </FilterField>
        <FilterField id="endpoint" label={t('deliveries.filters.endpoint')} className="min-w-[14rem]">
          <Select id="endpoint" value={endpointFilter} onChange={(e) => { setEndpointFilter(e.target.value); setPage(0); }}>
            <option value="">{t('deliveries.filters.allEndpoints')}</option>
            {endpoints.map(endpoint => (<option key={endpoint.id} value={endpoint.id}>{endpoint.url}</option>))}
          </Select>
        </FilterField>
        <FilterField id="dateRange" label={t('deliveries.filters.dateRange')}>
          <Select id="dateRange" value={dateRange} onChange={(e) => setDateRange(e.target.value)}>
            {dateRangeOptions(t).map(opt => (<option key={opt.value} value={opt.value}>{opt.label}</option>))}
          </Select>
        </FilterField>
        <SearchField
          id="search"
          label={t('deliveries.filters.searchByEventType')}
          placeholder={t('deliveries.filters.eventTypePlaceholder')}
          value={searchQuery}
          onChange={setSearchQuery}
        />
      </FilterBar>

      {deliveries.length === 0 ? (
        eventIdFilter && filteredEventType ? (
          <EmptyState
            icon={AlertTriangle}
            title={t('deliveries.noDeliveriesForEvent')}
            description={t('deliveries.noDeliveriesForEventPlain', { eventType: filteredEventType })}
            action={
              <Button onClick={() => navigate(`/admin/projects/${projectId}/subscriptions`)}>
                {t('deliveries.noDeliveriesForEventAction')}
              </Button>
            }
          />
        ) : (
          <EmptyState
            icon={Send}
            title={t('deliveries.noDeliveries')}
            description={t('deliveries.noDeliveriesDesc')}
            action={
              <Button variant="outline" onClick={() => navigate(`/admin/projects/${projectId}/events`)}>
                <Radio className="h-3.5 w-3.5" />
                {t('deliveries.goToEvents')}
              </Button>
            }
            docsLink="/docs#deliveries-api"
          />
        )
      ) : (
        <div className="animate-fade-in">
          <PermissionGate allowed={canReplayDeliveries}>
            <SelectionBar count={selectedCount} onClear={() => setSelectedIds(new Set())}>
              <VerificationGate>
                <Button size="sm" onClick={handleReplaySelected} disabled={bulkReplaying}>
                  {bulkReplaying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}
                  {t('deliveries.replaySelected', { count: selectedCount })}
                </Button>
              </VerificationGate>
            </SelectionBar>
          </PermissionGate>

          <div className="overflow-hidden rounded-lg border border-rail bg-card">
            <Table>
              <TableHeader>
                <TableRow>
                  {canReplayDeliveries && (
                    <TableHead className="w-10">
                      <SelectBox
                        checked={allSelected}
                        indeterminate={selectedCount > 0}
                        onChange={toggleAll}
                        label={t(allSelected ? 'common.deselectAll' : 'common.selectAll')}
                      />
                    </TableHead>
                  )}
                  <SortableTableHead field="status" sort={sort} onSort={toggleSort} className={SORTABLE_HEAD_CLASS}>{t('deliveries.columns.status')}</SortableTableHead>
                  <TableHead>{t('deliveries.columns.event')}</TableHead>
                  <TableHead>{t('deliveries.columns.endpoint')}</TableHead>
                  <SortableTableHead field="attemptCount" sort={sort} onSort={toggleSort} className={SORTABLE_HEAD_CLASS}>{t('deliveries.columns.attempts')}</SortableTableHead>
                  <SortableTableHead field="createdAt" sort={sort} onSort={toggleSort} className={cn(SORTABLE_HEAD_CLASS, 'hidden lg:table-cell')}>{t('deliveries.columns.created')}</SortableTableHead>
                  <TableHead className="hidden xl:table-cell">{t('deliveries.columns.deliveryId')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {deliveries.map((delivery) => {
                  const rail = railFromCounts(delivery.attemptCount, delivery.maxAttempts, delivery.status);
                  const explain = explainOf(delivery);
                  return (
                    <TableRow
                      key={delivery.id}
                      className="group/row cursor-pointer"
                      data-state={selectedIds.has(delivery.id) ? 'selected' : undefined}
                      onClick={() => setSelectedDeliveryId(delivery.id)}
                    >
                      {canReplayDeliveries && (
                        <TableCell>
                          {isReplayable(delivery) ? (
                            <SelectBox
                              checked={selectedIds.has(delivery.id)}
                              onChange={() => toggleRow(delivery.id)}
                              label={t('common.selectRow')}
                            />
                          ) : (
                            <span className="sr-only">{t('deliveries.nothingToReplay')}</span>
                          )}
                        </TableCell>
                      )}
                      <TableCell>
                        <span className="flex flex-col items-start gap-1">
                          <StatusBadge
                            kind={kindOfDeliveryStatus(delivery.status)}
                            label={t(`deliveries.status.${delivery.status}`)}
                          />
                          {explain && (
                            <span className="text-[11px] text-muted-foreground">{t(explain.key, explain.values)}</span>
                          )}
                        </span>
                      </TableCell>
                      <TableCell>
                        <CopyId
                          value={delivery.eventId}
                          to={`/admin/projects/${projectId}/events/${delivery.eventId}`}
                        />
                      </TableCell>
                      <TableCell>
                        <Link
                          to={`/admin/projects/${projectId}/endpoints`}
                          onClick={(e) => e.stopPropagation()}
                          className="block max-w-[220px] truncate rounded font-mono text-[13px] underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                          title={getEndpointName(delivery.endpointId)}
                        >
                          {getEndpointName(delivery.endpointId)}
                        </Link>
                      </TableCell>
                      <TableCell>
                        <AttemptCell
                          rail={rail.attempts}
                          maxAttempts={rail.maxAttempts}
                          attemptCount={delivery.attemptCount}
                          ladderLength={delivery.maxAttempts}
                          nextRetryAt={delivery.status === 'PENDING' ? delivery.nextRetryAt : undefined}
                        />
                      </TableCell>
                      <TableCell className="hidden lg:table-cell"><TimeCell value={delivery.createdAt} /></TableCell>
                      <TableCell className="hidden xl:table-cell"><CopyId value={delivery.id} /></TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>

          <TablePagination
            page={page}
            pageSize={pageSize}
            totalElements={totalElements}
            totalPages={totalPages}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
          />
        </div>
      )}

      <DeliveryDetailsSheet
        deliveryId={selectedDeliveryId}
        open={!!selectedDeliveryId}
        onClose={() => setSelectedDeliveryId(null)}
        onRefresh={refetchDeliveries}
      />

      <AlertDialog open={showBulkReplayDialog} onOpenChange={setShowBulkReplayDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('deliveries.bulkReplayDialog.title', { status: t(`deliveries.status.${statusFilter}`) })}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('deliveries.bulkReplayDialog.description', { status: t(`deliveries.status.${statusFilter}`), count: totalElements })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <Callout className="mx-1">{t('deliveries.bulkReplayDialog.warning')}</Callout>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={bulkReplaying}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={() => { handleReplayMatching(); setShowBulkReplayDialog(false); }} disabled={bulkReplaying}>
              {bulkReplaying && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('deliveries.bulkReplayDialog.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
