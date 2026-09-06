import { useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowDownToLine, Loader2, RotateCcw, Clock } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { formatDateTime } from '../lib/date';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge, { kindOfDeliveryStatus, type StatusKind } from '../components/StatusBadge';
import AttemptRail from '../components/AttemptRail';
import {
  useProject, useIncomingSources, useIncomingEvents, useIncomingEventAttempts, useReplayIncomingEvent,
  useBulkReplayIncomingEvents,
} from '../api/queries';
import type { IncomingEventResponse, IncomingForwardAttemptResponse } from '../types/api.types';
import { Button } from '../components/ui/button';
import { Select } from '../components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import {
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
} from '../components/ui/sheet';
import { usePermissions } from '../auth/usePermissions';
import { railFromForwardAttempts } from './attemptRailData';
import { CopyId, FilterBar, FilterField, SearchField, SelectBox, SelectionBar, TimeCell } from './tableParts';
import Callout from '../components/Callout';
import { formatJson } from '../lib/json';


/** Verification is the incoming direction's status: did this really come from the source. */
function verificationOf(event: IncomingEventResponse): { kind: StatusKind; key: string } {
  if (event.verified === true) return { kind: 'ok', key: 'incomingEvents.verified' };
  if (event.verified === false) return { kind: 'halt', key: 'incomingEvents.failed' };
  return { kind: 'idle', key: 'incomingEvents.noVerification' };
}


/**
 * Incoming events are the same table as outgoing events, read from the other
 * end: a Source instead of an Endpoint, a Forward instead of a Delivery. The
 * row components are shared with the outgoing side on purpose — a person should
 * not have to learn two tables to answer the same question twice.
 */
export default function IncomingEventsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const { canReplayIncomingEvents } = usePermissions();

  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [filterSourceId, setFilterSourceId] = useState('');
  const [verificationFilter, setVerificationFilter] = useState('');
  const [search, setSearch] = useState('');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const [replayEventId, setReplayEventId] = useState<string | null>(null);

  const {
    data: project, isLoading: projectLoading, isError: projectIsError, error: projectError, refetch: refetchProject,
  } = useProject(projectId);
  const {
    data: sourcesPage, isError: sourcesIsError, error: sourcesError, refetch: refetchSources,
  } = useIncomingSources(projectId, 0, 100);
  const sources = sourcesPage?.content ?? [];
  const {
    data: eventsPage, isLoading: eventsLoading, isError: eventsIsError, error: eventsError, refetch: refetchEvents,
  } = useIncomingEvents(projectId, { sourceId: filterSourceId || undefined, page, size: pageSize });
  const events = useMemo(() => eventsPage?.content ?? [], [eventsPage]);

  const visibleEvents = useMemo(() => events.filter((e) => {
    if (verificationFilter === 'verified' && e.verified !== true) return false;
    if (verificationFilter === 'failed' && e.verified !== false) return false;
    if (verificationFilter === 'none' && e.verified != null) return false;
    const q = search.trim().toLowerCase();
    if (q && !e.requestId.toLowerCase().includes(q) && !(e.sourceName ?? '').toLowerCase().includes(q)) return false;
    return true;
  }), [events, verificationFilter, search]);

  const selectedEvent = events.find((e) => e.id === selectedEventId) ?? null;
  const {
    data: attemptsPage, isLoading: loadingAttempts,
  } = useIncomingEventAttempts(projectId, selectedEventId ?? undefined);
  const attempts = useMemo(() => attemptsPage?.content ?? [], [attemptsPage]);

  /** Attempts belong to a Forward — one obligation per destination — so they group by it. */
  const forwards = useMemo(() => {
    const byDestination = new Map<string, IncomingForwardAttemptResponse[]>();
    for (const attempt of attempts) {
      const list = byDestination.get(attempt.destinationId) ?? [];
      list.push(attempt);
      byDestination.set(attempt.destinationId, list);
    }
    return Array.from(byDestination.entries()).map(([destinationId, list]) => {
      const ordered = [...list].sort((a, b) => a.attemptNumber - b.attemptNumber);
      const latest = ordered[ordered.length - 1];
      return { destinationId, attempts: ordered, latest, rail: railFromForwardAttempts(ordered) };
    });
  }, [attempts]);

  const loading = projectLoading || eventsLoading;
  const isError = projectIsError || eventsIsError || sourcesIsError;
  const retry = () => { refetchProject(); refetchEvents(); refetchSources(); };

  const replayMutation = useReplayIncomingEvent(projectId!);
  const bulkReplayMutation = useBulkReplayIncomingEvents(projectId!);
  const replaying = replayMutation.isPending || bulkReplayMutation.isPending;

  const selectedCount = selectedIds.size;
  const allSelected = visibleEvents.length > 0 && visibleEvents.every((e) => selectedIds.has(e.id));

  const toggleRow = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    setSelectedIds(allSelected ? new Set() : new Set(visibleEvents.map((e) => e.id)));
  };

  const handleReplay = async () => {
    if (!replayEventId || !projectId) return;
    try {
      const result = await replayMutation.mutateAsync(replayEventId);
      showSuccess(t('incomingEvents.toast.replayed', { count: result.destinationsCount }));
      setReplayEventId(null);
    } catch (err) {
      showApiError(err, 'incomingEvents.toast.replayFailed');
    }
  };

  /** Bulk replay is scoped to one Source per call, so a mixed selection fans out. */
  const handleReplaySelected = async () => {
    if (selectedCount === 0) return;
    const bySource = new Map<string, string[]>();
    for (const event of events) {
      if (!selectedIds.has(event.id)) continue;
      const list = bySource.get(event.incomingSourceId) ?? [];
      list.push(event.id);
      bySource.set(event.incomingSourceId, list);
    }
    try {
      let replayed = 0;
      for (const [sourceId, eventIds] of bySource) {
        const result = await bulkReplayMutation.mutateAsync({ sourceId, eventIds });
        replayed += result.eventsReplayed;
      }
      showSuccess(t('incomingEvents.toast.bulkReplayed', { count: replayed }));
      setSelectedIds(new Set());
    } catch (err) {
      showApiError(err, 'incomingEvents.toast.replayFailed');
    }
  };

  if (loading) {
    return <PageSkeleton maxWidth="max-w-none"><SkeletonRows count={5} height="h-16" /></PageSkeleton>;
  }

  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={projectError ?? eventsError ?? sourcesError} fallbackKey="incomingEvents.toast.loadFailed" onRetry={retry} />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('nav.incoming')}
        title={t('incomingEvents.pageTitle')}
        description={<Trans i18nKey="incomingEvents.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
      />

      <FilterBar>
        <FilterField id="incoming-source" label={t('incomingEvents.filters.source')} className="min-w-[12rem]">
          <Select id="incoming-source" value={filterSourceId} onChange={(e) => { setFilterSourceId(e.target.value); setPage(0); }}>
            <option value="">{t('incomingEvents.filters.allSources')}</option>
            {sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </Select>
        </FilterField>
        <FilterField id="incoming-verification" label={t('incomingEvents.columns.status')}>
          <Select id="incoming-verification" value={verificationFilter} onChange={(e) => setVerificationFilter(e.target.value)}>
            <option value="">{t('incomingEvents.filters.allVerifications')}</option>
            <option value="verified">{t('incomingEvents.verified')}</option>
            <option value="failed">{t('incomingEvents.failed')}</option>
            <option value="none">{t('incomingEvents.noVerification')}</option>
          </Select>
        </FilterField>
        <SearchField
          id="incoming-search"
          label={t('incomingEvents.columns.requestId')}
          placeholder={t('incomingEvents.filters.searchById')}
          value={search}
          onChange={setSearch}
        />
      </FilterBar>

      {events.length === 0 ? (
        <EmptyState
          icon={ArrowDownToLine}
          title={t('incomingEvents.noEvents')}
          description={sources.length === 0 ? t('incomingEvents.noEventsNoSourcesDesc') : t('incomingEvents.noEventsDesc')}
          action={sources.length === 0 ? (
            <Button onClick={() => navigate(`/admin/projects/${projectId}/incoming-sources`)}>
              <ArrowDownToLine className="h-4 w-4" /> {t('incomingEvents.createSourceFirst')}
            </Button>
          ) : undefined}
        />
      ) : (
        <div className="animate-fade-in">
          {canReplayIncomingEvents && (
            <SelectionBar count={selectedCount} onClear={() => setSelectedIds(new Set())}>
              <Button size="sm" onClick={handleReplaySelected} disabled={replaying}>
                {replaying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
                {t('incomingEvents.replaySelected', { count: selectedCount })}
              </Button>
            </SelectionBar>
          )}

          <div className="overflow-hidden rounded-lg border border-rail bg-card">
            <Table>
              <TableHeader>
                <TableRow>
                  {canReplayIncomingEvents && (
                    <TableHead className="w-10">
                      <SelectBox
                        checked={allSelected}
                        indeterminate={selectedCount > 0}
                        onChange={toggleAll}
                        label={t(allSelected ? 'common.deselectAll' : 'common.selectAll')}
                      />
                    </TableHead>
                  )}
                  <TableHead>{t('incomingEvents.columns.status')}</TableHead>
                  <TableHead>{t('incomingEvents.columns.source')}</TableHead>
                  <TableHead>{t('incomingEvents.columns.method')}</TableHead>
                  <TableHead>{t('incomingEvents.columns.requestId')}</TableHead>
                  <TableHead>{t('incomingEvents.columns.received')}</TableHead>
                  <TableHead className="w-[60px]"><span className="sr-only">{t('common.actions')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {visibleEvents.map((event) => {
                  const verification = verificationOf(event);
                  return (
                    <TableRow
                      key={event.id}
                      className="group/row cursor-pointer"
                      data-state={selectedIds.has(event.id) ? 'selected' : undefined}
                      onClick={() => setSelectedEventId(event.id)}
                    >
                      {canReplayIncomingEvents && (
                        <TableCell>
                          <SelectBox
                            checked={selectedIds.has(event.id)}
                            onChange={() => toggleRow(event.id)}
                            label={t('common.selectRow')}
                          />
                        </TableCell>
                      )}
                      <TableCell>
                        <span className="flex flex-col items-start gap-1">
                          <StatusBadge kind={verification.kind} label={t(verification.key)} />
                          {event.verificationError && (
                            <span className="block max-w-[180px] truncate text-[11px] text-muted-foreground" title={event.verificationError}>
                              {event.verificationError}
                            </span>
                          )}
                        </span>
                      </TableCell>
                      <TableCell>
                        <span className="text-[13px] font-medium">{event.sourceName || event.incomingSourceId.slice(0, 8)}</span>
                      </TableCell>
                      <TableCell>
                        <span className="font-mono text-[13px]">{event.method}</span>
                      </TableCell>
                      <TableCell><CopyId value={event.requestId} chars={12} /></TableCell>
                      <TableCell><TimeCell value={event.receivedAt} /></TableCell>
                      <TableCell>
                        {canReplayIncomingEvents && (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={(e) => { e.stopPropagation(); setReplayEventId(event.id); }}
                            title={t('incomingEvents.replay.submit')}
                            aria-label={t('incomingEvents.replay.submit')}
                          >
                            <RotateCcw className="h-3.5 w-3.5" />
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>

          {(verificationFilter || search.trim()) && (
            <p className="mt-3 text-xs text-muted-foreground">
              {t('events.filters.clientSideNote', { shown: visibleEvents.length, count: events.length })}
            </p>
          )}

          <TablePagination
            page={page}
            pageSize={pageSize}
            totalElements={eventsPage?.totalElements ?? 0}
            totalPages={eventsPage?.totalPages ?? 0}
            onPageChange={setPage}
            onPageSizeChange={(size) => { setPageSize(size); setPage(0); }}
          />
        </div>
      )}

      <Sheet open={!!selectedEvent} onOpenChange={(open) => !open && setSelectedEventId(null)}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
          <SheetHeader>
            <SheetTitle>{t('incomingEvents.detail.title')}</SheetTitle>
            <SheetDescription className="font-mono">{selectedEvent?.requestId}</SheetDescription>
          </SheetHeader>

          {selectedEvent && (
            <div className="mt-6 space-y-6">
              <section className="rounded-lg border border-rail">
                <h3 className="border-b border-rail px-4 py-2.5 text-[13px] font-medium">{t('incomingEvents.detail.requestMeta')}</h3>
                <dl className="divide-y divide-rail text-[13px]">
                  {[
                    { label: t('incomingEvents.columns.method'), value: selectedEvent.method },
                    { label: t('incomingEvents.columns.source'), value: selectedEvent.sourceName || selectedEvent.incomingSourceId },
                    { label: t('incomingEvents.columns.contentType'), value: selectedEvent.contentType || '—' },
                    { label: t('incomingEvents.columns.clientIp'), value: selectedEvent.clientIp || '—' },
                    { label: t('incomingEvents.columns.received'), value: formatDateTime(selectedEvent.receivedAt) },
                  ].map((row) => (
                    <div key={row.label} className="flex items-center justify-between gap-4 px-4 py-2">
                      <dt className="text-muted-foreground">{row.label}</dt>
                      <dd className="truncate font-mono">{row.value}</dd>
                    </div>
                  ))}
                  <div className="flex items-center justify-between gap-4 px-4 py-2">
                    <dt className="text-muted-foreground">{t('incomingEvents.detail.signatureResult')}</dt>
                    <dd>
                      <StatusBadge kind={verificationOf(selectedEvent).kind} label={t(verificationOf(selectedEvent).key)} />
                    </dd>
                  </div>
                </dl>
                {selectedEvent.verificationError && (
                  <p className="border-t border-rail bg-halt-soft px-4 py-2 text-[13px] text-halt">{selectedEvent.verificationError}</p>
                )}
              </section>

              <section className="rounded-lg border border-rail">
                <div className="flex items-center justify-between border-b border-rail px-4 py-2.5">
                  <h3 className="text-[13px] font-medium">{t('incomingEvents.detail.forwards')}</h3>
                  {canReplayIncomingEvents && (
                    <Button size="sm" variant="outline" onClick={() => setReplayEventId(selectedEvent.id)}>
                      <RotateCcw className="h-3.5 w-3.5" /> {t('incomingEvents.replay.submit')}
                    </Button>
                  )}
                </div>
                <div className="p-4">
                  {loadingAttempts ? (
                    <SkeletonRows count={2} height="h-16" />
                  ) : forwards.length === 0 ? (
                    <EmptyState
                      icon={Clock}
                      title={t('incomingEvents.detail.noAttempts')}
                      className="flex flex-col items-center justify-center py-6"
                    />
                  ) : (
                    <div className="space-y-6">
                      {forwards.map((forward) => (
                        <div key={forward.destinationId} className="space-y-3">
                          <div className="flex flex-wrap items-center justify-between gap-2">
                            <span className="truncate font-mono text-[13px]" title={forward.latest.destinationUrl}>
                              {forward.latest.destinationUrl || forward.destinationId}
                            </span>
                            <StatusBadge
                              kind={kindOfDeliveryStatus(forward.latest.status)}
                              label={t(`deliveries.status.${forward.latest.status}`)}
                            />
                          </div>
                          <AttemptRail
                            attempts={forward.rail.attempts}
                            maxAttempts={forward.rail.maxAttempts}
                            size="full"
                            ariaLabel={t('deliveries.rail.label', { count: forward.attempts.length, total: forward.rail.maxAttempts })}
                          />
                          <ul className="space-y-2">
                            {forward.attempts.map((attempt) => (
                              <li key={attempt.id} className="rounded-md border border-rail p-3 text-[12px]">
                                <div className="flex flex-wrap items-center gap-2">
                                  <span className="font-medium">{t('incomingEvents.detail.attempt', { number: attempt.attemptNumber })}</span>
                                  {attempt.responseCode != null && (
                                    <span className="font-mono text-muted-foreground">{attempt.responseCode}</span>
                                  )}
                                  {attempt.startedAt && (
                                    <span className="ml-auto font-mono text-[11px] text-muted-foreground">{formatDateTime(attempt.startedAt)}</span>
                                  )}
                                </div>
                                {attempt.errorMessage && (
                                  <p className="mt-1.5 rounded bg-halt-soft p-2 text-halt">{attempt.errorMessage}</p>
                                )}
                                {attempt.nextRetryAt && (
                                  <p className="mt-1.5 flex items-center gap-1 text-retry">
                                    <Clock className="h-3 w-3" aria-hidden />
                                    {t('incomingEvents.detail.nextRetry', { time: formatDateTime(attempt.nextRetryAt) })}
                                  </p>
                                )}
                                {/* What we sent, not only what came back. The Forward's request
                                    headers and body are recorded server-side — masked through
                                    HeaderSanitizer and truncated there — and showing them is what
                                    brings debugging a Forward level with debugging a Delivery,
                                    which has shown both for as long as it has existed. Collapsed
                                    by default: the response is what a reader looks at first, and
                                    "what did we send them" is the second question. */}
                                {attempt.requestHeadersJson && (
                                  <details className="mt-1.5">
                                    <summary className="cursor-pointer text-[11px] text-muted-foreground hover:text-foreground">
                                      {t('incomingEvents.detail.attemptRequestHeaders')}
                                    </summary>
                                    <pre className="mt-1 max-h-24 overflow-auto whitespace-pre-wrap break-all rounded bg-secondary p-2 font-mono text-[11px]">
                                      {formatJson(attempt.requestHeadersJson)}
                                    </pre>
                                  </details>
                                )}
                                {attempt.requestBodySnippet && (
                                  <details className="mt-1.5">
                                    <summary className="cursor-pointer text-[11px] text-muted-foreground hover:text-foreground">
                                      {t('incomingEvents.detail.attemptRequestBody')}
                                    </summary>
                                    <pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap break-all rounded bg-secondary p-2 font-mono text-[11px]">
                                      {formatJson(attempt.requestBodySnippet)}
                                    </pre>
                                  </details>
                                )}
                                {attempt.responseBodySnippet && (
                                  <pre className="mt-1.5 max-h-24 overflow-auto whitespace-pre-wrap break-all rounded bg-secondary p-2 font-mono text-[11px]">
                                    {attempt.responseBodySnippet}
                                  </pre>
                                )}
                              </li>
                            ))}
                          </ul>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </section>

              {selectedEvent.headersJson && (
                <section className="rounded-lg border border-rail">
                  <h3 className="border-b border-rail px-4 py-2.5 text-[13px] font-medium">{t('incomingEvents.detail.headers')}</h3>
                  <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all p-4 font-mono text-[11px]">
                    {formatJson(selectedEvent.headersJson)}
                  </pre>
                </section>
              )}

              {selectedEvent.bodyRaw && (
                <section className="rounded-lg border border-rail">
                  <h3 className="border-b border-rail px-4 py-2.5 text-[13px] font-medium">{t('incomingEvents.detail.body')}</h3>
                  <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all p-4 font-mono text-[11px]">
                    {formatJson(selectedEvent.bodyRaw)}
                  </pre>
                </section>
              )}
            </div>
          )}
        </SheetContent>
      </Sheet>

      <AlertDialog open={!!replayEventId} onOpenChange={(open) => !open && setReplayEventId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('incomingEvents.replay.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('incomingEvents.replay.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <Callout className="mx-1">{t('incomingEvents.replay.idempotencyWarning')}</Callout>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={replaying}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleReplay} disabled={replaying}>
              {replaying && <Loader2 className="h-4 w-4 animate-spin" />}
              {replaying ? t('incomingEvents.replay.replaying') : t('incomingEvents.replay.submit')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
