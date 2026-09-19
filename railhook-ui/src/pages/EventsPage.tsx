import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { Radio, Plus, Share2, Loader2 } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import { useEvents, useProject } from '../api/queries';
import PageSkeleton from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import IntegrationSnippet from '../components/IntegrationSnippet';
import { sendEventSnippets } from '../lib/integrationSnippets';
import { apiBaseUrl } from '../lib/publicSnippets';
import StatusBadge, { type StatusKind } from '../components/StatusBadge';
import { Button, buttonVariants } from '../components/ui/button';
import { Select } from '../components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { SortableTableHead, useSort } from '../components/ui/sortable-table-head';
import { TablePagination } from '../components/ui/table-pagination';
import EventDetailsSheet from '../components/EventDetailsSheet';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { debugLinksApi } from '../api/debugLinks.api';
import { CopyId, FilterBar, FilterField, SearchField, SORTABLE_HEAD_CLASS, TimeCell } from './tableParts';
import type { DeliveryStatusCounts } from '../types/api.types';
import { useDebounced } from '../hooks/useDebounced';


/**
 * What happened to the Deliveries one Event owed.
 *
 * An Event has no status of its own — it exists whether or not anyone was
 * listening — so the only question this page can answer, and the reason it
 * exists, is what became of the Deliveries it created. The event list carries
 * each Event's Delivery counts by status, so every row is exact on any page and
 * for any fan-out.
 */
type EventStatus = 'delivered' | 'owed' | 'abandoned' | 'unsubscribed';

function deliveredOf(counts: DeliveryStatusCounts | undefined) {
  if (!counts) return { delivered: 0, total: 0 };
  return {
    delivered: counts.success,
    total: counts.pending + counts.processing + counts.success + counts.failed + counts.dlq,
  };
}

function statusOf(counts: DeliveryStatusCounts | undefined): EventStatus {
  if (!counts || deliveredOf(counts).total === 0) return 'unsubscribed';
  if (counts.dlq > 0 || counts.failed > 0) return 'abandoned';
  if (counts.pending > 0 || counts.processing > 0) return 'owed';
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
};

const STATUS_FILTERS: EventStatus[] = ['delivered', 'owed', 'abandoned', 'unsubscribed'];

export default function EventsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const { sort, toggle: toggleSort, param: sortParam } = useSort('createdAt', 'desc');
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

  const rows = useMemo(() => events.map((event) => ({
    event,
    delivered: deliveredOf(event.deliveryCounts),
    status: statusOf(event.deliveryCounts),
  })), [events]);

  const visibleRows = statusFilter ? rows.filter((r) => r.status === statusFilter) : rows;

  // First load only: a search or page change keeps the previous rows (keepPreviousData), so
  // the search input the user is typing into is not unmounted by a skeleton.
  const loading = eventsLoading && !eventsData;
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
        {/* The test console is the one place a test event is sent from: it also shows what each
            endpoint answered, which a fire-and-forget dialog here could not. */}
        <Link to={`/admin/projects/${projectId}/test-console`} className={buttonVariants()}>
          <Plus className="h-4 w-4" aria-hidden /> {t('events.sendTest')}
        </Link>
      </VerificationGate>
    </PermissionGate>
  );

  const sendSnippet = (
    <IntegrationSnippet
      title={t('events.snippet.title')}
      samples={sendEventSnippets({ baseUrl: apiBaseUrl() })}
      footer={t('events.snippet.footer')}
      docsLink="start/send-first-webhook"
    />
  );
  const noEventsYet = events.length === 0 && !search;

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('nav.outgoing')}
        title={t('nav.outgoingEvents')}
        description={<Trans i18nKey="events.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
        actions={sendAction}
        guide={{ id: 'events', docsLink: 'start/send-first-webhook', children: noEventsYet ? undefined : sendSnippet }}
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
        <>
          <EmptyState
            icon={Radio}
            title={search ? t('common.noResults') : t('events.noEvents')}
            description={search ? t('events.noMatchDesc') : t('events.noEventsDesc')}
            action={search ? (
              <Button variant="outline" onClick={() => setSearch('')}>{t('common.clearSearch')}</Button>
            ) : sendAction}
            docsLink={search ? undefined : 'api-reference'}
          />
          {noEventsYet && <div className="mt-6">{sendSnippet}</div>}
        </>
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
                {visibleRows.map(({ event, delivered, status }) => (
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
                        {t('events.deliveredOf', delivered)}
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
    </div>
  );
}
