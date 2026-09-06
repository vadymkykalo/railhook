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
  useProject, useIncomingDlq, useIncomingDlqStats,
  useIncomingDlqRetry, useIncomingDlqBulkRetry, useIncomingDlqPurge,
} from '../api/queries';
import { Button } from '../components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import DangerConfirmDialog from '../components/DangerConfirmDialog';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { railFromCounts } from './attemptRailData';
import { AttemptCell, CopyId, SelectBox, SelectionBar, TimeCell } from './tableParts';

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
 * The same page as the outgoing DLQ, read from the other end: a Forward that Railhook
 * stopped trying to get to a Destination, rather than a Delivery it stopped trying to get
 * to an Endpoint.
 *
 * One difference is worth knowing while looking at it. Retrying here re-forwards to the one
 * Destination that failed and starts a fresh Retry Ladder for it; the Time Machine's replay,
 * which was the only recovery before this page existed, fans an Incoming Event out to every
 * enabled Destination — including the ones that already received it.
 */
export default function IncomingDlqPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageDlq } = usePermissions();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [showPurgeDialog, setShowPurgeDialog] = useState(false);

  const {
    data: project, isLoading: projectLoading, isError: projectIsError, error: projectError, refetch: refetchProject,
  } = useProject(projectId);
  const {
    data: dlqData, isLoading: dlqLoading, isError: dlqIsError, error: dlqError, refetch: refetchDlq,
  } = useIncomingDlq(projectId, page, pageSize);
  const { data: stats, refetch: refetchStats } = useIncomingDlqStats(projectId);

  const items = useMemo(() => dlqData?.content ?? [], [dlqData]);
  const totalElements = dlqData?.totalElements ?? 0;
  const totalPages = dlqData?.totalPages ?? 0;

  const loading = projectLoading || dlqLoading;
  const isError = projectIsError || dlqIsError;
  const retry = () => { refetchProject(); refetchDlq(); refetchStats(); };

  const retrySingleMutation = useIncomingDlqRetry(projectId!);
  const retryBulkMutation = useIncomingDlqBulkRetry(projectId!);
  const purgeMutation = useIncomingDlqPurge(projectId!);
  const retrying = retrySingleMutation.isPending || retryBulkMutation.isPending;
  const purging = purgeMutation.isPending;

  const selectedCount = selectedIds.size;
  const allSelected = items.length > 0 && items.every((i) => selectedIds.has(i.forwardAttemptId));

  const handleRetrySingle = async (forwardAttemptId: string) => {
    try {
      await retrySingleMutation.mutateAsync(forwardAttemptId);
      showSuccess(t('incomingDlq.toast.retried'));
      refetchStats();
    } catch (err: unknown) {
      showApiError(err, 'incomingDlq.toast.retryFailed');
    }
  };

  const handleRetrySelected = async () => {
    if (selectedCount === 0) return;
    try {
      const result = await retryBulkMutation.mutateAsync(Array.from(selectedIds));
      showSuccess(t('incomingDlq.toast.bulkRetried', { count: result.retried }));
      setSelectedIds(new Set());
      refetchStats();
    } catch (err: unknown) {
      showApiError(err, 'incomingDlq.toast.bulkRetryFailed');
    }
  };

  const handlePurgeAll = async () => {
    try {
      const result = await purgeMutation.mutateAsync();
      showCriticalSuccess(t('incomingDlq.toast.purged', { count: result.purged }));
      setShowPurgeDialog(false);
      setSelectedIds(new Set());
      refetchStats();
    } catch (err: unknown) {
      showApiError(err, 'incomingDlq.toast.purgeFailed');
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
    setSelectedIds(allSelected ? new Set() : new Set(items.map((i) => i.forwardAttemptId)));
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
        <ErrorState error={projectError ?? dlqError} fallbackKey="incomingDlq.toast.loadFailed" onRetry={retry} />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('nav.incoming')}
        title={t('incomingDlq.pageTitle')}
        description={<Trans i18nKey="incomingDlq.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
        actions={
          <PermissionGate allowed={canManageDlq}>
            <VerificationGate>
              <Button variant="destructive" onClick={() => setShowPurgeDialog(true)} disabled={!stats?.totalItems}>
                <Trash2 className="h-3.5 w-3.5" /> {t('incomingDlq.purgeAll')}
              </Button>
            </VerificationGate>
          </PermissionGate>
        }
      />

      {stats && (
        <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Metric label={t('incomingDlq.totalItems')} value={stats.totalItems} halt />
          <Metric label={t('incomingDlq.last24h')} value={stats.last24Hours} />
          <Metric label={t('incomingDlq.last7d')} value={stats.last7Days} />
        </div>
      )}

      {items.length === 0 ? (
        <EmptyState icon={CheckCircle2} title={t('incomingDlq.noItems')} description={t('incomingDlq.noItemsDesc')} docsLink="/docs#retries" />
      ) : (
        <div className="animate-fade-in">
          <PermissionGate allowed={canManageDlq}>
            <SelectionBar count={selectedCount} onClear={() => setSelectedIds(new Set())}>
              <VerificationGate>
                <Button size="sm" onClick={handleRetrySelected} disabled={retrying}>
                  {retrying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
                  {t('incomingDlq.retrySelected', { count: selectedCount })}
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
                  <TableHead>{t('incomingDlq.columns.source')}</TableHead>
                  <TableHead>{t('incomingDlq.columns.destination')}</TableHead>
                  <TableHead>{t('incomingDlq.columns.attempts')}</TableHead>
                  <TableHead className="hidden lg:table-cell">{t('incomingDlq.columns.lastError')}</TableHead>
                  <TableHead>{t('incomingDlq.columns.failedAt')}</TableHead>
                  <TableHead>{t('incomingDlq.columns.eventId')}</TableHead>
                  {canManageDlq && <TableHead className="w-[60px]"><span className="sr-only">{t('common.actions')}</span></TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((item) => {
                  const attemptCount = item.attemptNumber ?? 0;
                  const ladderLength = item.maxAttempts ?? attemptCount;
                  const rail = railFromCounts(attemptCount, ladderLength, 'DLQ');
                  return (
                    <TableRow
                      key={item.forwardAttemptId}
                      className="group/row"
                      data-state={selectedIds.has(item.forwardAttemptId) ? 'selected' : undefined}
                    >
                      {canManageDlq && (
                        <TableCell>
                          <SelectBox
                            checked={selectedIds.has(item.forwardAttemptId)}
                            onChange={() => toggleRow(item.forwardAttemptId)}
                            label={t('common.selectRow')}
                          />
                        </TableCell>
                      )}
                      <TableCell>
                        <span className="flex flex-col items-start gap-1">
                          <StatusBadge kind="halt" label={t('incomingDlq.abandoned')} />
                          <span className="text-[11px] text-muted-foreground">
                            {t('incomingDlq.ladderExhausted', { count: attemptCount })}
                          </span>
                        </span>
                      </TableCell>
                      <TableCell><code className="font-mono text-[13px]">{item.sourceName || '—'}</code></TableCell>
                      <TableCell>
                        <span className="block max-w-[200px] truncate font-mono text-[13px]" title={item.destinationUrl}>
                          {item.destinationUrl || '—'}
                        </span>
                      </TableCell>
                      <TableCell>
                        <AttemptCell
                          rail={rail.attempts}
                          maxAttempts={rail.maxAttempts}
                          attemptCount={attemptCount}
                          ladderLength={ladderLength}
                        />
                      </TableCell>
                      <TableCell className="hidden lg:table-cell">
                        <span className="block max-w-[220px] truncate text-[13px] text-halt" title={item.lastError ?? undefined}>
                          {item.lastError || t('incomingDlq.unknownError')}
                        </span>
                      </TableCell>
                      <TableCell><TimeCell value={item.failedAt ?? item.createdAt ?? ''} /></TableCell>
                      <TableCell><CopyId value={item.incomingEventId} /></TableCell>
                      {canManageDlq && (
                        <TableCell>
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={() => handleRetrySingle(item.forwardAttemptId)}
                            disabled={retrying}
                            title={t('incomingDlq.retryOne')}
                            aria-label={t('incomingDlq.retryOne')}
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

      <DangerConfirmDialog
        open={showPurgeDialog}
        onOpenChange={setShowPurgeDialog}
        title={t('incomingDlq.purgeDialog.title')}
        description={t('incomingDlq.purgeDialog.description', { count: stats?.totalItems })}
        confirmName={project?.name || ''}
        impact={[
          t('incomingDlq.purgeDialog.impactItems', { count: stats?.totalItems || 0 }),
          t('incomingDlq.purgeDialog.impactIrreversible'),
        ]}
        onConfirm={handlePurgeAll}
        loading={purging}
        confirmLabel={t('incomingDlq.purgeAll')}
      />
    </div>
  );
}
