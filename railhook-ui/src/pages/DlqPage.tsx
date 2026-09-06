import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { RotateCcw, Trash2, Loader2, CheckCircle2 } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showCriticalSuccess } from '../lib/toast';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import {
  useProject, useDlq, useDlqStats, useEndpoints, useDlqRetry, useDlqBulkRetry, useDlqPurge,
} from '../api/queries';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { Select } from '../components/ui/select';
import { TablePagination } from '../components/ui/table-pagination';
import DangerConfirmDialog from '../components/DangerConfirmDialog';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { railFromCounts } from './attemptRailData';
import { AttemptCell, CopyId, FilterBar, FilterField, SearchField, SelectBox, SelectionBar, TimeCell } from './tableParts';
import DeliveryDetailsSheet from './DeliveryDetailsSheet';


/** A number worth reading on its own, in the machine voice. */
function Metric({ label, value, halt }: { label: string; value: number; halt?: boolean }) {
  return (
    <div className="rounded-lg border border-rail bg-card px-4 py-3">
      <p className="mono-label">{label}</p>
      <p className={`mt-1 font-mono text-2xl ${halt ? 'text-halt' : 'text-foreground'}`}>{value}</p>
    </div>
  );
}

/**
 * The DLQ is not a separate product — it is the Deliveries table filtered down
 * to the obligations Railhook has stopped trying: every row's Retry Ladder is
 * exhausted, so every row is `halt`, and the only question left is whether a
 * human wants it replayed or purged.
 */
export default function DlqPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageDlq } = usePermissions();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [endpointFilter, setEndpointFilter] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [showPurgeDialog, setShowPurgeDialog] = useState(false);
  const [selectedDeliveryId, setSelectedDeliveryId] = useState<string | null>(null);

  const dlqFilters = {
    endpointId: endpointFilter || undefined,
    search: searchQuery || undefined,
    dateFrom: dateFrom || undefined,
    dateTo: dateTo || undefined,
  };

  const {
    data: project, isLoading: projectLoading, isError: projectIsError, error: projectError, refetch: refetchProject,
  } = useProject(projectId);
  const {
    data: dlqData, isLoading: dlqLoading, isError: dlqIsError, error: dlqError, refetch: refetchDlq,
  } = useDlq(projectId, page, pageSize, dlqFilters);
  const { data: stats, refetch: refetchStats } = useDlqStats(projectId);
  const { data: endpoints = [] } = useEndpoints(projectId);
  const items = useMemo(() => dlqData?.content ?? [], [dlqData]);
  const totalElements = dlqData?.totalElements ?? 0;
  const totalPages = dlqData?.totalPages ?? 0;

  const loading = projectLoading || dlqLoading;
  const isError = projectIsError || dlqIsError;
  const retry = () => { refetchProject(); refetchDlq(); refetchStats(); };

  const replaySingleMutation = useDlqRetry(projectId!);
  const replayBulkMutation = useDlqBulkRetry(projectId!);
  const purgeMutation = useDlqPurge(projectId!);
  const replaying = replaySingleMutation.isPending || replayBulkMutation.isPending;
  const purging = purgeMutation.isPending;

  const selectedCount = selectedIds.size;
  const allSelected = items.length > 0 && items.every((i) => selectedIds.has(i.deliveryId));

  const handleReplaySingle = async (deliveryId: string) => {
    try {
      await replaySingleMutation.mutateAsync(deliveryId);
      showSuccess(t('dlq.toast.replayed'));
      refetchStats();
    } catch (err: any) {
      showApiError(err, 'dlq.toast.replayFailed');
    }
  };

  const handleReplaySelected = async () => {
    if (selectedCount === 0) return;
    try {
      const result = await replayBulkMutation.mutateAsync(Array.from(selectedIds));
      showSuccess(t('dlq.toast.bulkReplayed', { count: result.retried }));
      setSelectedIds(new Set());
      refetchStats();
    } catch (err: any) {
      showApiError(err, 'dlq.toast.bulkReplayFailed');
    }
  };

  const handlePurgeAll = async () => {
    try {
      const result = await purgeMutation.mutateAsync();
      showCriticalSuccess(t('dlq.toast.purged', { count: result.purged }));
      setShowPurgeDialog(false);
      setSelectedIds(new Set());
      refetchStats();
    } catch (err: any) {
      showApiError(err, 'dlq.toast.purgeFailed');
    }
  };

  const toggleRow = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    setSelectedIds(allSelected ? new Set() : new Set(items.map((i) => i.deliveryId)));
  };

  if (loading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={3} height="h-20" cols="grid-cols-3" />
        <div className="h-[300px] animate-pulse rounded-xl bg-muted" />
      </PageSkeleton>
    );
  }

  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={projectError ?? dlqError} fallbackKey="dlq.toast.loadFailed" onRetry={retry} />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('nav.outgoing')}
        description={<Trans i18nKey="dlq.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
        actions={
          <PermissionGate allowed={canManageDlq}>
            <VerificationGate>
              <Button variant="destructive" onClick={() => setShowPurgeDialog(true)} disabled={!stats?.totalItems}>
                <Trash2 className="h-3.5 w-3.5" /> {t('dlq.purgeAll')}
              </Button>
            </VerificationGate>
          </PermissionGate>
        }
      />

      {stats && (
        <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Metric label={t('dlq.totalItems')} value={stats.totalItems} halt />
          <Metric label={t('dlq.last24h')} value={stats.last24Hours} />
          <Metric label={t('dlq.last7d')} value={stats.last7Days} />
        </div>
      )}

      <FilterBar>
        <FilterField id="dlq-endpoint" label={t('dlq.filterEndpoint')} className="min-w-[14rem]">
          <Select id="dlq-endpoint" value={endpointFilter} onChange={(e) => { setEndpointFilter(e.target.value); setPage(0); }}>
            <option value="">{t('dlq.allEndpoints')}</option>
            {endpoints.map(endpoint => (<option key={endpoint.id} value={endpoint.id}>{endpoint.url}</option>))}
          </Select>
        </FilterField>
        <FilterField id="dlq-from" label={t('dlq.filterFrom')}>
          <Input id="dlq-from" type="date" value={dateFrom} onChange={(e) => { setDateFrom(e.target.value); setPage(0); }} />
        </FilterField>
        <FilterField id="dlq-to" label={t('dlq.filterTo')}>
          <Input id="dlq-to" type="date" value={dateTo} onChange={(e) => { setDateTo(e.target.value); setPage(0); }} />
        </FilterField>
        <SearchField
          id="dlq-search"
          label={t('common.search')}
          placeholder={t('dlq.searchPlaceholder')}
          value={searchQuery}
          onChange={(value) => { setSearchQuery(value); setPage(0); }}
        />
      </FilterBar>

      {items.length === 0 ? (
        <EmptyState icon={CheckCircle2} title={t('dlq.noItems')} description={t('dlq.noItemsDesc')} docsLink="/docs#retries" />
      ) : (
        <div className="animate-fade-in">
          <PermissionGate allowed={canManageDlq}>
            <SelectionBar count={selectedCount} onClear={() => setSelectedIds(new Set())}>
              <VerificationGate>
                <Button size="sm" onClick={handleReplaySelected} disabled={replaying}>
                  {replaying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
                  {t('dlq.replaySelected', { count: selectedCount })}
                </Button>
              </VerificationGate>
            </SelectionBar>
          </PermissionGate>

          <div className="overflow-hidden rounded-lg border border-rail bg-card">
            <Table>
              <TableHeader>
                <TableRow>
                  {canManageDlq && (
                    <TableHead className="w-10">
                      <SelectBox
                        checked={allSelected}
                        indeterminate={selectedCount > 0}
                        onChange={toggleAll}
                        label={t(allSelected ? 'common.deselectAll' : 'common.selectAll')}
                      />
                    </TableHead>
                  )}
                  <TableHead>{t('deliveries.columns.status')}</TableHead>
                  <TableHead>{t('dlq.columns.eventType')}</TableHead>
                  <TableHead>{t('dlq.columns.endpoint')}</TableHead>
                  <TableHead>{t('dlq.columns.attempts')}</TableHead>
                  <TableHead className="hidden lg:table-cell">{t('dlq.columns.lastError')}</TableHead>
                  <TableHead>{t('dlq.columns.failedAt')}</TableHead>
                  <TableHead>{t('deliveries.columns.deliveryId')}</TableHead>
                  {canManageDlq && <TableHead className="w-[60px]"><span className="sr-only">{t('common.actions')}</span></TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((item) => {
                  const rail = railFromCounts(item.attemptCount, item.maxAttempts, 'DLQ');
                  return (
                    <TableRow
                      key={item.deliveryId}
                      className="group/row cursor-pointer"
                      data-state={selectedIds.has(item.deliveryId) ? 'selected' : undefined}
                      onClick={() => setSelectedDeliveryId(item.deliveryId)}
                    >
                      {canManageDlq && (
                        <TableCell onClick={(e) => e.stopPropagation()}>
                          <SelectBox
                            checked={selectedIds.has(item.deliveryId)}
                            onChange={() => toggleRow(item.deliveryId)}
                            label={t('common.selectRow')}
                          />
                        </TableCell>
                      )}
                      <TableCell>
                        <span className="flex flex-col items-start gap-1">
                          <StatusBadge kind="halt" label={t('dlq.abandoned')} />
                          <span className="text-[11px] text-muted-foreground">
                            {t('dlq.ladderExhausted', { count: item.attemptCount })}
                          </span>
                        </span>
                      </TableCell>
                      <TableCell><code className="font-mono text-[13px]">{item.eventType}</code></TableCell>
                      <TableCell>
                        <span className="block max-w-[200px] truncate font-mono text-[13px]" title={item.endpointUrl}>{item.endpointUrl}</span>
                      </TableCell>
                      <TableCell>
                        <AttemptCell
                          rail={rail.attempts}
                          maxAttempts={rail.maxAttempts}
                          attemptCount={item.attemptCount}
                          ladderLength={item.maxAttempts}
                        />
                      </TableCell>
                      <TableCell className="hidden lg:table-cell">
                        <span className="block max-w-[220px] truncate text-[13px] text-halt" title={item.lastError ?? undefined}>
                          {item.lastError || t('dlq.unknownError')}
                        </span>
                      </TableCell>
                      <TableCell><TimeCell value={item.failedAt} /></TableCell>
                      <TableCell><CopyId value={item.deliveryId} /></TableCell>
                      {canManageDlq && (
                        <TableCell>
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={(e) => { e.stopPropagation(); handleReplaySingle(item.deliveryId); }}
                            disabled={replaying}
                            title={t('dlq.replayOne')}
                            aria-label={t('dlq.replayOne')}
                          >
                            <RotateCcw className="h-3.5 w-3.5" />
                          </Button>
                        </TableCell>
                      )}
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
            onPageSizeChange={(size) => { setPageSize(size); setPage(0); }}
          />
        </div>
      )}

      {/* A DLQ item is a Delivery that ran out of ladder, and the sheet that
          shows a Delivery's whole attempt history — every request, every
          response, replay — already exists. `dlqApi.getItem` returns the same
          nine fields the row above already has, so calling it would cost a
          request and tell the reader nothing new. */}
      <DeliveryDetailsSheet
        deliveryId={selectedDeliveryId}
        open={!!selectedDeliveryId}
        onClose={() => setSelectedDeliveryId(null)}
        onRefresh={() => { refetchDlq(); refetchStats(); }}
      />

      <DangerConfirmDialog
        open={showPurgeDialog}
        onOpenChange={setShowPurgeDialog}
        title={t('dlq.purgeDialog.title')}
        description={t('dlq.purgeDialog.description', { count: stats?.totalItems })}
        confirmName={project?.name || ''}
        impact={[
          t('dlq.purgeDialog.impactItems', { count: stats?.totalItems || 0 }),
          t('dlq.purgeDialog.impactIrreversible'),
        ]}
        onConfirm={handlePurgeAll}
        loading={purging}
        confirmLabel={t('dlq.purgeAll')}
      />
    </div>
  );
}
