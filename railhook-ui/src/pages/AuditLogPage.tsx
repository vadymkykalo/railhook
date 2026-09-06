import { useState, useMemo, useCallback } from 'react';
import { FileText, Download, Loader2, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuditLog } from '../api/queries';
import { formatDateTimeCompact } from '../lib/date';
import { SkeletonTable } from '../components/PageSkeleton';
import JsonBlock from '../components/JsonBlock';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { auditLogApi, type AuditLogEntry, type AuditLogFilters } from '../api/auditLog.api';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Select } from '../components/ui/select';
import { Input } from '../components/ui/input';
import { showSuccess, showApiError } from '../lib/toast';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../components/ui/dialog';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';

const ALL_ACTIONS = [
  'CREATE', 'UPDATE', 'DELETE', 'ROTATE_SECRET', 'REVOKE',
  'REGISTER', 'LOGIN', 'LOGOUT', 'CONFIGURE_MTLS', 'TEST_WEBHOOK',
  'PASSWORD_RESET_REQUESTED', 'PASSWORD_RESET', 'PASSWORD_CHANGED',
  'MEMBER_INVITED', 'MEMBER_ROLE_CHANGED', 'MEMBER_REMOVED',
  'MEMBER_SUSPENDED', 'MEMBER_REINSTATED',
  'INVITE_ACCEPTED', 'RESOLVE_INCIDENT',
];

const ALL_RESOURCE_TYPES = [
  'Endpoint', 'Subscription', 'ApiKey', 'Project', 'Member',
  'AlertRule', 'Incident', 'IncidentTimeline',
  'IncomingSource', 'IncomingDestination', 'Transformation', 'SchemaRegistry',
  'Auth',
];

function shortId(id: string | null) {
  if (!id) return '—';
  return `${id.substring(0, 8)}…`;
}

/**
 * The log of who did what. An audit action is not a status — only the outcome
 * of the attempt is — so the actions are set in the machine voice and the four
 * status colours stay reserved for the SUCCESS/FAILURE column.
 */
export default function AuditLogPage() {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [actionFilter, setActionFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [resourceTypeFilter, setResourceTypeFilter] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [exporting, setExporting] = useState(false);
  const [selected, setSelected] = useState<AuditLogEntry | null>(null);

  const filters: AuditLogFilters = useMemo(() => ({
    action: actionFilter || undefined,
    status: statusFilter || undefined,
    resourceType: resourceTypeFilter || undefined,
    from: dateFrom || undefined,
    to: dateTo || undefined,
  }), [actionFilter, statusFilter, resourceTypeFilter, dateFrom, dateTo]);

  const { data, isLoading, isError, error, refetch, isRefetching } = useAuditLog(page, pageSize, filters);

  const hasFilters = !!(actionFilter || statusFilter || resourceTypeFilter || dateFrom || dateTo);

  const clearFilters = () => {
    setActionFilter('');
    setStatusFilter('');
    setResourceTypeFilter('');
    setDateFrom('');
    setDateTo('');
    setPage(0);
  };

  const applyFilter = useCallback((setter: (v: string) => void) => (e: { target: { value: string } }) => {
    setter(e.target.value);
    setPage(0);
  }, []);

  const handleExportCsv = async () => {
    setExporting(true);
    try {
      const blob = await auditLogApi.exportCsv(filters);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `audit-log-${new Date().toISOString().slice(0, 10)}.csv`;
      a.click();
      URL.revokeObjectURL(url);
      showSuccess(t('auditLog.export.done', { count: data?.totalElements ?? 0 }));
    } catch (err: any) {
      showApiError(err, 'auditLog.export.failed');
    } finally {
      setExporting(false);
    }
  };

  const actionLabel = (action: string) => t(`auditLog.actions.${action}`, { defaultValue: action });

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={data ? t('auditLog.eventCount', { count: data.totalElements }) : undefined}
        title={t('auditLog.title')}
        description={t('auditLog.subtitle')}
        actions={data && data.totalElements > 0 ? (
          <Button variant="outline" onClick={handleExportCsv} disabled={exporting}>
            {exporting ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Download className="h-4 w-4" aria-hidden />}
            {t('auditLog.export.csv')}
          </Button>
        ) : undefined}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Select
          value={actionFilter}
          onChange={applyFilter(setActionFilter)}
          aria-label={t('auditLog.filters.allActions')}
          className="h-9 w-full sm:w-44"
        >
          <option value="">{t('auditLog.filters.allActions')}</option>
          {ALL_ACTIONS.map((action) => (
            <option key={action} value={action}>{actionLabel(action)}</option>
          ))}
        </Select>
        <Select
          value={resourceTypeFilter}
          onChange={applyFilter(setResourceTypeFilter)}
          aria-label={t('auditLog.filters.allResources')}
          className="h-9 w-full sm:w-44"
        >
          <option value="">{t('auditLog.filters.allResources')}</option>
          {ALL_RESOURCE_TYPES.map((rt) => (
            <option key={rt} value={rt}>{rt}</option>
          ))}
        </Select>
        <Select
          value={statusFilter}
          onChange={applyFilter(setStatusFilter)}
          aria-label={t('auditLog.filters.allStatuses')}
          className="h-9 w-full sm:w-36"
        >
          <option value="">{t('auditLog.filters.allStatuses')}</option>
          <option value="SUCCESS">{t('auditLog.filters.success')}</option>
          <option value="FAILURE">{t('auditLog.filters.failure')}</option>
        </Select>
        <Input
          type="date" value={dateFrom} onChange={applyFilter(setDateFrom)}
          aria-label={t('auditLog.filters.from')} className="h-9 w-full sm:w-36"
        />
        <Input
          type="date" value={dateTo} onChange={applyFilter(setDateTo)}
          aria-label={t('auditLog.filters.to')} className="h-9 w-full sm:w-36"
        />
        {hasFilters && (
          <Button variant="ghost" size="sm" onClick={clearFilters}>
            <X className="h-3.5 w-3.5" aria-hidden /> {t('auditLog.filters.clear')}
          </Button>
        )}
      </div>

      {isError ? (
        <ErrorState error={error} fallbackKey="auditLog.loadFailed" onRetry={() => refetch()} retrying={isRefetching} />
      ) : isLoading ? (
        <Card className="overflow-hidden"><SkeletonTable rows={8} /></Card>
      ) : !data || data.content.length === 0 ? (
        <EmptyState
          icon={FileText}
          title={t(hasFilters ? 'auditLog.noMatches' : 'auditLog.noLogs')}
          description={t(hasFilters ? 'auditLog.noMatchesDesc' : 'auditLog.noLogsDesc')}
          action={hasFilters ? <Button variant="outline" onClick={clearFilters}>{t('auditLog.filters.clear')}</Button> : undefined}
        />
      ) : (
        <Card className="overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[150px]">{t('auditLog.columns.time')}</TableHead>
                <TableHead className="w-[150px]">{t('auditLog.columns.action')}</TableHead>
                <TableHead className="w-[200px]">{t('auditLog.columns.resource')}</TableHead>
                <TableHead>{t('auditLog.columns.user')}</TableHead>
                <TableHead className="w-[110px]">{t('auditLog.columns.status')}</TableHead>
                <TableHead className="w-[90px] text-right">{t('auditLog.columns.duration')}</TableHead>
                <TableHead className="w-[80px]"><span className="sr-only">{t('common.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.content.map((entry: AuditLogEntry) => (
                <TableRow key={entry.id}>
                  <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">
                    {formatDateTimeCompact(entry.createdAt)}
                  </TableCell>
                  <TableCell className="font-mono text-[13px]">{actionLabel(entry.action)}</TableCell>
                  <TableCell className="font-mono text-[13px]">
                    {entry.resourceType}
                    <span className="ml-1.5 text-muted-foreground" title={entry.resourceId || undefined}>
                      {shortId(entry.resourceId)}
                    </span>
                  </TableCell>
                  <TableCell className="truncate font-mono text-[13px] text-muted-foreground" title={entry.userId || undefined}>
                    {entry.userEmail || (entry.userId ? shortId(entry.userId) : '—')}
                  </TableCell>
                  <TableCell>
                    <StatusBadge
                      kind={entry.status === 'SUCCESS' ? 'ok' : 'halt'}
                      label={t(entry.status === 'SUCCESS' ? 'auditLog.filters.success' : 'auditLog.filters.failure')}
                    />
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs text-muted-foreground">
                    {entry.durationMs != null ? `${entry.durationMs}ms` : '—'}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button variant="ghost" size="sm" onClick={() => setSelected(entry)}>
                      {t('auditLog.detail.open')}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      {!isError && !isLoading && data && data.content.length > 0 && (
        <TablePagination
          page={page}
          pageSize={pageSize}
          totalElements={data.totalElements}
          totalPages={data.totalPages}
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
        />
      )}

      <Dialog open={!!selected} onOpenChange={(open) => { if (!open) setSelected(null); }}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{t('auditLog.detail.title')}</DialogTitle>
          </DialogHeader>
          {selected && (
            <div className="space-y-0.5">
              <DetailRow label={t('auditLog.columns.time')} value={formatDateTimeCompact(selected.createdAt)} mono />
              <DetailRow label={t('auditLog.columns.action')} value={actionLabel(selected.action)} mono />
              <DetailRow label={t('auditLog.columns.status')}>
                <StatusBadge
                  kind={selected.status === 'SUCCESS' ? 'ok' : 'halt'}
                  label={t(selected.status === 'SUCCESS' ? 'auditLog.filters.success' : 'auditLog.filters.failure')}
                />
              </DetailRow>
              <DetailRow label={t('auditLog.columns.resource')} value={selected.resourceType} mono />
              <DetailRow label={t('auditLog.columns.resourceId')} value={selected.resourceId || '—'} mono />
              <DetailRow label={t('auditLog.columns.user')} value={selected.userEmail || '—'} mono />
              <DetailRow label={t('auditLog.columns.duration')} value={selected.durationMs != null ? `${selected.durationMs}ms` : '—'} mono />
              <DetailRow label={t('auditLog.columns.ip')} value={selected.clientIp || '—'} mono />
              {selected.details && (
                <div className="pt-3">
                  <JsonBlock label={t('auditLog.detail.changes')} value={selected.details} maxHeight="max-h-48" />
                </div>
              )}
              {selected.errorMessage && (
                <div className="pt-3">
                  <p className="mono-label mb-1.5">{t('auditLog.columns.error')}</p>
                  <p className="break-all rounded-lg border border-halt/30 bg-halt-soft p-3 text-[13px] text-halt">
                    {selected.errorMessage}
                  </p>
                </div>
              )}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function DetailRow({ label, value, mono, children }: { label: string; value?: string; mono?: boolean; children?: React.ReactNode }) {
  return (
    <div className="flex items-baseline gap-3 border-b border-rail py-2 last:border-b-0">
      <span className="mono-label w-32 flex-shrink-0">{label}</span>
      {children || <span className={`min-w-0 break-all text-[13px] ${mono ? 'font-mono' : ''}`}>{value}</span>}
    </div>
  );
}
