import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  ArrowRight, ChevronDown, ChevronUp, Flame, Loader2, MessageSquare, Plus, RotateCcw,
  Search as SearchIcon, Send, XCircle, CheckCircle2,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import {
  useIncidents, useIncident, useCreateIncident, useUpdateIncident, useAddTimelineEntry, useOpenIncidentCount,
} from '../api/queries';
import type { IncidentStatus, IncidentTimelineType } from '../api/incidents.api';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import { Textarea } from '../components/ui/textarea';
import { TablePagination } from '../components/ui/table-pagination';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { cn } from '../lib/utils';
import {
  STATUS_FILL, STATUS_TEXT, StatTile, formatCompact, kindOfIncidentStatus, kindOfSeverity,
} from '../components/charts';

const SEVERITY_VALUES = ['INFO', 'WARNING', 'CRITICAL'] as const;

const TIMELINE_ICON: Record<IncidentTimelineType, React.ElementType> = {
  FAILURE: XCircle,
  RETRY: RotateCcw,
  REPLAY: Send,
  NOTE: MessageSquare,
  STATUS_CHANGE: ArrowRight,
};

/** A timeline entry's type is a lifecycle state, so it maps onto the same four. */
const TIMELINE_KIND = {
  FAILURE: 'halt',
  RETRY: 'retry',
  REPLAY: 'retry',
  NOTE: 'idle',
  STATUS_CHANGE: 'idle',
} as const;

export default function IncidentsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageEndpoints } = usePermissions();

  const [openOnly, setOpenOnly] = useState(true);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [showNoteDialog, setShowNoteDialog] = useState<string | null>(null);

  const [formTitle, setFormTitle] = useState('');
  const [formSeverity, setFormSeverity] = useState<string>('WARNING');
  const [noteTitle, setNoteTitle] = useState('');
  const [noteDetail, setNoteDetail] = useState('');

  const {
    data: incidentsData, isLoading, isError, error, refetch,
  } = useIncidents(projectId, openOnly, page, pageSize);
  const { data: openCount } = useOpenIncidentCount(projectId);
  const createIncident = useCreateIncident(projectId!);
  const updateIncident = useUpdateIncident(projectId!);
  const addTimeline = useAddTimelineEntry(projectId!);
  const { data: expandedIncident } = useIncident(projectId, expandedId ?? undefined);

  const incidents = incidentsData?.content ?? [];
  // All three come from the server, all three span the project. They used to be one real
  // count beside two derived from `incidents` — which is one page of a filtered list, so a
  // project with more open incidents than fit on a page read "Critical: 0" with a critical
  // incident open on page two.
  const openIncidents = openCount?.count ?? 0;
  const investigating = openCount?.investigating ?? 0;
  const critical = openCount?.critical ?? 0;

  const handleCreate = async () => {
    try {
      await createIncident.mutateAsync({ title: formTitle, severity: formSeverity });
      showSuccess(t('incidents.toast.created'));
      setShowCreateDialog(false);
      setFormTitle('');
      setFormSeverity('WARNING');
    } catch (err: any) {
      showApiError(err, 'incidents.toast.createFailed');
    }
  };

  const handleStatusChange = async (incidentId: string, status: IncidentStatus) => {
    try {
      await updateIncident.mutateAsync({ incidentId, data: { status } });
      showSuccess(t('incidents.toast.statusUpdated'));
    } catch (err: any) {
      showApiError(err, 'incidents.toast.updateFailed');
    }
  };

  const handleSaveRca = async (incidentId: string, rcaNotes: string) => {
    try {
      await updateIncident.mutateAsync({ incidentId, data: { rcaNotes } });
      showSuccess(t('incidents.toast.rcaSaved'));
    } catch (err: any) {
      showApiError(err, 'incidents.toast.updateFailed');
    }
  };

  const handleAddNote = async () => {
    if (!showNoteDialog) return;
    try {
      await addTimeline.mutateAsync({
        incidentId: showNoteDialog,
        data: { entryType: 'NOTE' as IncidentTimelineType, title: noteTitle, detail: noteDetail || undefined },
      });
      showSuccess(t('incidents.toast.noteAdded'));
      setShowNoteDialog(null);
      setNoteTitle('');
      setNoteDetail('');
    } catch (err: any) {
      showApiError(err, 'incidents.toast.addNoteFailed');
    }
  };

  if (isLoading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={3} height="h-[104px]" cols="grid-cols-1 lg:grid-cols-3" />
        <SkeletonCards count={3} height="h-20" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        title={t('incidents.title')}
        description={t('incidents.subtitle')}
        actions={
          <PermissionGate allowed={canManageEndpoints}>
            <VerificationGate>
              <Button onClick={() => setShowCreateDialog(true)}>
                <Plus className="h-4 w-4" /> {t('incidents.create')}
              </Button>
            </VerificationGate>
          </PermissionGate>
        }
      />

      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-3">
          <StatTile
            label={t('incidents.tiles.open')}
            value={formatCompact(openIncidents)}
            hint={t('incidents.tiles.openHint')}
            badge={openIncidents > 0
              ? <StatusBadge kind="halt" label={t('incidents.statuses.OPEN')} icon={false} />
              : <StatusBadge kind="ok" label={t('incidents.tiles.allClear')} icon={false} />}
          />
          <StatTile
            label={t('incidents.tiles.investigating')}
            value={formatCompact(investigating)}
            hint={t('incidents.tiles.investigatingHint')}
          />
          <StatTile
            label={t('incidents.tiles.critical')}
            value={formatCompact(critical)}
            hint={t('incidents.tiles.criticalHint')}
            badge={critical > 0 ? <StatusBadge kind="halt" label={t('alerts.severities.CRITICAL')} icon={false} /> : undefined}
          />
        </div>

        {/* One filter row, above what it scopes. */}
        <div className="flex flex-wrap items-center gap-2">
          <div
            role="group"
            aria-label={t('incidents.filterLabel')}
            className="inline-flex rounded-lg border border-rail bg-card p-0.5"
          >
            {([true, false] as const).map((only) => (
              <button
                key={String(only)}
                type="button"
                onClick={() => { setOpenOnly(only); setPage(0); }}
                aria-pressed={openOnly === only}
                className={cn(
                  'rounded-md px-3 py-1.5 text-xs transition-colors',
                  openOnly === only
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:text-foreground'
                )}
              >
                {t(only ? 'incidents.openOnly' : 'incidents.showAll')}
              </button>
            ))}
          </div>
        </div>

        {isError ? (
          <ErrorState error={error} fallbackKey="incidents.loadFailed" onRetry={() => refetch()} />
        ) : incidents.length === 0 ? (
          <EmptyState
            icon={Flame}
            title={t('incidents.empty')}
            description={t('incidents.emptyDesc')}
            action={
              <PermissionGate allowed={canManageEndpoints}>
                <VerificationGate>
                  <Button onClick={() => setShowCreateDialog(true)}>
                    <Plus className="h-4 w-4" /> {t('incidents.create')}
                  </Button>
                </VerificationGate>
              </PermissionGate>
            }
          />
        ) : (
          <div className="animate-fade-in space-y-3">
            {incidents.map((incident) => {
              const isExpanded = expandedId === incident.id;
              const statusKind = kindOfIncidentStatus(incident.status);
              const severityKind = kindOfSeverity(incident.severity);
              return (
                <Card key={incident.id} className="overflow-hidden">
                  <button
                    type="button"
                    className="flex w-full items-start gap-3 p-4 text-left transition-colors hover:bg-secondary/40"
                    onClick={() => setExpandedId(isExpanded ? null : incident.id)}
                    aria-expanded={isExpanded}
                  >
                    {/* The severity rule: colour and position, before any words. */}
                    <span
                      aria-hidden
                      className={cn('mt-0.5 h-9 w-1 flex-shrink-0 rounded-full', STATUS_FILL[severityKind])}
                    />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">{incident.title}</span>
                      <span className="mt-1.5 flex flex-wrap items-center gap-2">
                        <StatusBadge kind={statusKind} label={t(`incidents.statuses.${incident.status}`)} />
                        <StatusBadge
                          kind={severityKind}
                          label={t(`alerts.severities.${incident.severity}`)}
                          icon={false}
                        />
                        <span className="text-[11px] text-muted-foreground">
                          {formatRelativeTime(incident.createdAt)}
                        </span>
                      </span>
                    </span>
                    {isExpanded
                      ? <ChevronUp className="h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />
                      : <ChevronDown className="h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />}
                  </button>

                  {isExpanded && expandedIncident && (
                    <div className="space-y-4 border-t border-rail p-4">
                      <div className="flex flex-wrap items-center gap-2">
                        <Button variant="outline" size="sm" asChild>
                          <Link to={`/admin/projects/${projectId}/deliveries?status=FAILED`}>
                            <SearchIcon className="h-3.5 w-3.5" />
                            {t('incidents.investigateDeliveries')}
                          </Link>
                        </Button>
                        {canManageEndpoints && (
                          <>
                            {expandedIncident.status !== 'INVESTIGATING' && (
                              <Button variant="outline" size="sm" onClick={() => handleStatusChange(incident.id, 'INVESTIGATING')}>
                                <SearchIcon className="h-3.5 w-3.5" /> {t('incidents.investigate')}
                              </Button>
                            )}
                            {expandedIncident.status !== 'RESOLVED' && (
                              <Button variant="outline" size="sm" onClick={() => handleStatusChange(incident.id, 'RESOLVED')}>
                                <CheckCircle2 className="h-3.5 w-3.5" /> {t('incidents.resolve')}
                              </Button>
                            )}
                            {expandedIncident.status === 'RESOLVED' && (
                              <Button variant="outline" size="sm" onClick={() => handleStatusChange(incident.id, 'OPEN')}>
                                <XCircle className="h-3.5 w-3.5" /> {t('incidents.reopen')}
                              </Button>
                            )}
                            <Button variant="outline" size="sm" onClick={() => setShowNoteDialog(incident.id)}>
                              <MessageSquare className="h-3.5 w-3.5" /> {t('incidents.addNote')}
                            </Button>
                          </>
                        )}
                      </div>

                      <div className="space-y-2">
                        <Label htmlFor={`rca-${incident.id}`} className="text-xs font-medium">
                          {t('incidents.rcaNotes')}
                        </Label>
                        <Textarea
                          id={`rca-${incident.id}`}
                          className="min-h-[80px] text-sm"
                          placeholder={t('incidents.rcaPlaceholder')}
                          defaultValue={expandedIncident.rcaNotes || ''}
                          onBlur={(e) => {
                            const val = e.target.value;
                            if (val !== (expandedIncident.rcaNotes || '')) handleSaveRca(incident.id, val);
                          }}
                        />
                      </div>

                      {expandedIncident.timeline && expandedIncident.timeline.length > 0 && (
                        <div className="space-y-2">
                          <p className="mono-label">{t('incidents.timeline')}</p>
                          <ul className="relative space-y-3 border-l border-rail py-1 pl-6">
                            {expandedIncident.timeline.map((entry) => {
                              const EntryIcon = TIMELINE_ICON[entry.entryType] ?? ArrowRight;
                              const kind = TIMELINE_KIND[entry.entryType] ?? 'idle';
                              return (
                                <li key={entry.id} className="relative">
                                  <span className="absolute -left-[31px] top-0.5 flex h-4 w-4 items-center justify-center rounded-full border border-rail bg-card">
                                    <EntryIcon className={cn('h-2.5 w-2.5', STATUS_TEXT[kind])} aria-hidden />
                                  </span>
                                  <span className="flex flex-wrap items-baseline gap-2">
                                    <span className="text-sm font-medium">{entry.title}</span>
                                    <span className="text-[11px] text-muted-foreground">
                                      {formatRelativeTime(entry.createdAt)}
                                    </span>
                                  </span>
                                  {entry.detail && (
                                    <p className="mt-0.5 text-xs text-muted-foreground">{entry.detail}</p>
                                  )}
                                  {entry.deliveryId && (
                                    <Link
                                      to={`/admin/projects/${projectId}/deliveries?deliveryId=${entry.deliveryId}`}
                                      className="mt-0.5 block font-mono text-[11px] text-muted-foreground hover:text-foreground hover:underline"
                                    >
                                      {entry.deliveryId}
                                    </Link>
                                  )}
                                </li>
                              );
                            })}
                          </ul>
                        </div>
                      )}

                      {expandedIncident.resolvedAt && (
                        <p className="text-xs text-muted-foreground">
                          {t('incidents.resolvedAtLabel', { time: formatDateTime(expandedIncident.resolvedAt) })}
                        </p>
                      )}
                    </div>
                  )}
                </Card>
              );
            })}

            {incidentsData && (
              <TablePagination
                page={page}
                pageSize={pageSize}
                totalElements={incidentsData.totalElements}
                totalPages={incidentsData.totalPages}
                onPageChange={setPage}
                onPageSizeChange={setPageSize}
              />
            )}
          </div>
        )}
      </div>

      <Dialog open={showCreateDialog} onOpenChange={setShowCreateDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('incidents.createDialog.title')}</DialogTitle>
            <DialogDescription>{t('incidents.createDialog.desc')}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label htmlFor="incident-title">{t('incidents.form.title')}</Label>
              <Input
                id="incident-title"
                value={formTitle}
                onChange={(e) => setFormTitle(e.target.value)}
                placeholder={t('incidents.form.titlePlaceholder')}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="incident-severity">{t('incidents.form.severity')}</Label>
              <Select id="incident-severity" value={formSeverity} onChange={(e) => setFormSeverity(e.target.value)}>
                {SEVERITY_VALUES.map((v) => <option key={v} value={v}>{t(`alerts.severities.${v}`)}</option>)}
              </Select>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowCreateDialog(false)}>{t('common.cancel')}</Button>
            <Button onClick={handleCreate} disabled={!formTitle || createIncident.isPending}>
              {createIncident.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('incidents.createDialog.submit')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!showNoteDialog} onOpenChange={() => setShowNoteDialog(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('incidents.noteDialog.title')}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label htmlFor="note-title">{t('incidents.noteDialog.noteTitle')}</Label>
              <Input
                id="note-title"
                value={noteTitle}
                onChange={(e) => setNoteTitle(e.target.value)}
                placeholder={t('incidents.noteDialog.titlePlaceholder')}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="note-detail">{t('incidents.noteDialog.detail')}</Label>
              <Textarea
                id="note-detail"
                value={noteDetail}
                onChange={(e) => setNoteDetail(e.target.value)}
                placeholder={t('incidents.noteDialog.detailPlaceholder')}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowNoteDialog(null)}>{t('common.cancel')}</Button>
            <Button onClick={handleAddNote} disabled={!noteTitle || addTimeline.isPending}>
              {addTimeline.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('incidents.noteDialog.submit')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
