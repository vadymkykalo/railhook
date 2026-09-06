import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { Radio, Plus, Share2, Loader2 } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import { useEvents, useProject, useDeliveries } from '../api/queries';
import PageSkeleton from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge, { type StatusKind } from '../components/StatusBadge';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '../components/ui/button';
import { Select } from '../components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { SortableTableHead, useSort } from '../components/ui/sortable-table-head';
import { TablePagination } from '../components/ui/table-pagination';
import SendTestEventModal from '../components/SendTestEventModal';
import EventDetailsSheet from '../components/EventDetailsSheet';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { debugLinksApi } from '../api/debugLinks.api';
import { CopyId, FilterBar, FilterField, SearchField, SORTABLE_HEAD_CLASS, TimeCell } from './tableParts';
import type { DeliveryResponse } from '../types/api.types';
import { useDebounced } from '../hooks/useDebounced';


/**
 * What happened to the Deliveries one Event owed.
 *
 * An Event has no status of its own — it exists whether or not anyone was
 * listening — so the only question this page can answer, and the reason it
 * exists, is what became of the Deliveries it created. That rollup is derived
 * here rather than served: no endpoint returns it, so the page fetches the
 * Deliveries created in the same window as the Events on screen and joins them
 * by event id.
 */
interface Rollup {
  total: number;
  delivered: number;
  owed: number;
  abandoned: number;
}

type EventStatus = 'delivered' | 'owed' | 'abandoned' | 'unsubscribed' | 'unknown';

function statusOf(rollup: Rollup | undefined, deliveriesCreated: number | undefined): EventStatus {
  if (deliveriesCreated === 0) return 'unsubscribed';
  if (!rollup || rollup.total === 0) return 'unknown';
  if (rollup.abandoned > 0) return 'abandoned';
  if (rollup.owed > 0) return 'owed';
  return 'delivered';
}

/**
 * Exported for the locale test: a Record over the union means TypeScript
 * guarantees these keys are the complete set of statuses this page can render,
 * so the test does not have to restate them and cannot fall behind.
 */
export const STATUS_KIND: Record<EventStatus, StatusKind> = {
  delivered: 'ok',
  owed: 'retry',
  abandoned: 'halt',
  unsubscribed: 'idle',
  unknown: 'idle',
};

function rollupOf(deliveries: DeliveryResponse[]): Map<string, Rollup> {
  const byEvent = new Map<string, Rollup>();
  for (const d of deliveries) {
    const r = byEvent.get(d.eventId) ?? { total: 0, delivered: 0, owed: 0, abandoned: 0 };
    r.total += 1;
    if (d.status === 'SUCCESS') r.delivered += 1;
    else if (d.status === 'DLQ' || d.status === 'FAILED') r.abandoned += 1;
    else r.owed += 1;
    byEvent.set(d.eventId, r);
  }
  return byEvent;
}

const STATUS_FILTERS: EventStatus[] = ['delivered', 'owed', 'abandoned', 'unsubscribed'];

export default function EventsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const { sort, toggle: toggleSort, param: sortParam } = useSort('createdAt', 'desc');
  const [showSendModal, setShowSendModal] = useState(false);
  const { canSendEvents, canCreateDebugLinks } = usePermissions();
  const [sharingEventId, setSharingEventId] = useState<string | null>(null);
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const debouncedSearch = useDebounced(search.trim());

  useEffect(() => setPage(0), [debouncedSearch]);

  const { data: project, isError: projectIsError, error: projectError, refetch: refetchProject } = useProject(projectId);

  const {
    data: eventsData, isLoading: eventsLoading, isError: eventsIsError, error: eventsError, refetch: refetchEvents,
  } = useEvents(projectId, page, pageSize, sortParam, debouncedSearch || undefined);
  const events = useMemo(() => eventsData?.content ?? [], [eventsData]);
  const totalElements = eventsData?.totalElements ?? 0;
  const totalPages = eventsData?.totalPages ?? 0;

  // One request, not one per row: the window that covers the events on screen.
  const fromDate = useMemo(() => {
    if (events.length === 0) return undefined;
    const oldest = Math.min(...events.map((e) => new Date(e.createdAt).getTime()));
    return new Date(oldest - 60_000).toISOString();
  }, [events]);

  const { data: deliveriesData } = useDeliveries(fromDate ? projectId : undefined, {
    page: 0,
    size: 200,
    sort: 'createdAt,desc',
    fromDate,
  });
  const rollups = useMemo(() => rollupOf(deliveriesData?.content ?? []), [deliveriesData]);

  const rows = useMemo(() => events.map((event) => ({
    event,
    rollup: rollups.get(event.id),
    status: statusOf(rollups.get(event.id), event.deliveriesCreated),
  })), [events, rollups]);

  const visibleRows = statusFilter ? rows.filter((r) => r.status === statusFilter) : rows;

  const loading = eventsLoading;
  const isError = projectIsError || eventsIsError;
  const retry = () => { refetchProject(); refetchEvents(); };

  const handleShareDebugLink = async (eventId: string) => {
    if (!projectId) return;
    try {
      setSharingEventId(eventId);
      const link = await debugLinksApi.create(projectId, eventId);
      await navigator.clipboard.writeText(link.shareUrl);
      showSuccess(t('debugLinks.copied'));
    } catch (err: any) {
      showApiError(err, 'debugLinks.createFailed');
    } finally {
      setSharingEventId(null);
    }
  };

  if (loading) {
    return <PageSkeleton maxWidth="max-w-none" />;
  }

  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={projectError ?? eventsError} fallbackKey="events.toast.loadFailed" onRetry={retry} />
      </div>
    );
  }

  const sendAction = (
    <PermissionGate allowed={canSendEvents}>
      <VerificationGate>
        <Button onClick={() => setShowSendModal(true)}>
          <Plus className="h-4 w-4" /> {t('events.sendTest')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('nav.outgoing')}
        description={<Trans i18nKey="events.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
        actions={sendAction}
      />

      <FilterBar>
        <FilterField id="event-status" label={t('events.filters.deliveryStatus')}>
          <Select id="event-status" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="">{t('events.filters.allStatuses')}</option>
            {STATUS_FILTERS.map((s) => (
              <option key={s} value={s}>{t(`events.deliveryStatus.${s}`)}</option>
            ))}
          </Select>
        </FilterField>
        <SearchField
          id="event-search"
          label={t('events.filters.eventType')}
          placeholder={t('events.filters.eventTypePlaceholder')}
          value={search}
          onChange={setSearch}
        />
      </FilterBar>

      {events.length === 0 ? (
        <EmptyState
          icon={Radio}
          title={search ? t('common.noResults') : t('events.noEvents')}
          description={search ? t('events.noMatchDesc') : t('events.noEventsDesc')}
          action={search ? (
            <Button variant="outline" onClick={() => setSearch('')}>{t('common.clearSearch')}</Button>
          ) : sendAction}
          docsLink={search ? undefined : '/docs#events-api'}
        />
      ) : (
        <div className="animate-fade-in">
          <div className="overflow-hidden rounded-lg border border-rail bg-card">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('deliveries.columns.status')}</TableHead>
                  <SortableTableHead field="eventType" sort={sort} onSort={toggleSort} className={SORTABLE_HEAD_CLASS}>{t('events.eventType')}</SortableTableHead>
                  <TableHead>{t('events.deliveriesCount')}</TableHead>
                  <TableHead>{t('events.eventId')}</TableHead>
                  <SortableTableHead field="createdAt" sort={sort} onSort={toggleSort} className={SORTABLE_HEAD_CLASS}>{t('events.created')}</SortableTableHead>
                  <TableHead className="w-[60px]"><span className="sr-only">{t('common.actions')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {visibleRows.map(({ event, rollup, status }) => (
                  <TableRow
                    key={event.id}
                    className="group/row cursor-pointer"
                    onClick={() => setSelectedEventId(event.id)}
                  >
                    <TableCell>
                      <StatusBadge kind={STATUS_KIND[status]} label={t(`events.deliveryStatus.${status}`)} />
                    </TableCell>
                    <TableCell>
                      <Link
                        to={`/admin/projects/${projectId}/events/${event.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="rounded font-mono text-[13px] font-medium underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        {event.eventType}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <Link
                        to={`/admin/projects/${projectId}/deliveries?eventId=${event.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="rounded font-mono text-[13px] text-muted-foreground underline-offset-4 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        {rollup
                          ? t('events.deliveredOf', { delivered: rollup.delivered, total: rollup.total })
                          : t('events.deliveredOf', { delivered: 0, total: event.deliveriesCreated ?? 0 })}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <CopyId value={event.id} to={`/admin/projects/${projectId}/events/${event.id}`} />
                    </TableCell>
                    <TableCell><TimeCell value={event.createdAt} /></TableCell>
                    <TableCell>
                      {canCreateDebugLinks && (
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          onClick={(e) => { e.stopPropagation(); handleShareDebugLink(event.id); }}
                          disabled={sharingEventId === event.id}
                          title={t('debugLinks.share')}
                          aria-label={t('debugLinks.share')}
                        >
                          {sharingEventId === event.id ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : (
                            <Share2 className="h-3.5 w-3.5" />
                          )}
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>

          {statusFilter && (
            <p className="mt-3 text-xs text-muted-foreground">
              {t('events.filters.clientSideNote', { shown: visibleRows.length, count: rows.length })}
            </p>
          )}

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

      <EventDetailsSheet
        projectId={projectId!}
        eventId={selectedEventId}
        onClose={() => setSelectedEventId(null)}
        onViewDeliveries={(id) => {
          setSelectedEventId(null);
          navigate(`/admin/projects/${projectId}/deliveries?eventId=${id}`);
        }}
      />

      <SendTestEventModal
        projectId={projectId!}
        open={showSendModal}
        onClose={() => setShowSendModal(false)}
        onSuccess={() => queryClient.invalidateQueries({ queryKey: ['events', projectId] })}
      />
    </div>
  );
}
