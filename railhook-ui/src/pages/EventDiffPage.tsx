import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { GitCompare, Search, ChevronLeft, ChevronRight, AlertTriangle, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError } from '../lib/toast';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import { eventDiffApi, type EventDiffResponse } from '../api/eventDiff.api';
import { eventsApi, type EventResponse } from '../api/events.api';
import EventDiffView from '../components/EventDiffView';
import {
  Workbench, WorkbenchPanel, RunControl, ResultFrame, ResultMetric, ResultPlaceholder,
} from '../components/Workbench';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Switch } from '../components/ui/switch';
import { Label } from '../components/ui/label';
import { formatDateTime, formatRelativeTime } from '../lib/date';

// ── Searchable event picker ─────────────────────────────────────────

interface EventPickerProps {
  label: string;
  events: EventResponse[];
  selectedId: string;
  onSelect: (id: string) => void;
  totalPages: number;
  currentPage: number;
  onPageChange: (page: number) => void;
  searchQuery: string;
  onSearchChange: (q: string) => void;
  loading: boolean;
  /** The caught error from the last event fetch, if it failed. */
  error?: unknown;
  onRetry: () => void;
  totalElements: number;
}

function EventPicker({
  label, events, selectedId, onSelect, totalPages, currentPage,
  onPageChange, searchQuery, onSearchChange, loading, error, onRetry, totalElements,
}: EventPickerProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const selected = events.find((e) => e.id === selectedId);

  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, []);

  return (
    <div className="space-y-1.5" ref={wrapperRef}>
      <span className="mono-label">{label}</span>
      <div className="relative">
        {/* A div, not a <button>: it hosts a real nested clear-button, and HTML
            forbids interactive content inside <button>. */}
        <div
          role="button"
          tabIndex={0}
          aria-expanded={open}
          className={`flex w-full cursor-pointer items-center justify-between gap-2 rounded-md border border-rail bg-card px-3 py-2 text-left text-sm transition-colors hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${selectedId ? '' : 'text-muted-foreground'}`}
          onClick={() => setOpen(!open)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              setOpen(!open);
            }
          }}
        >
          {selected ? (
            <span className="min-w-0 truncate">
              <span className="font-mono font-medium">{selected.eventType}</span>
              <span className="ml-2 font-mono text-xs text-muted-foreground">
                {selected.id.substring(0, 8)} · {formatRelativeTime(selected.createdAt)}
              </span>
            </span>
          ) : (
            <span>{t('eventDiff.selectPlaceholder')}</span>
          )}
          {selectedId && (
            <button
              type="button"
              className="flex-shrink-0 rounded-sm text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              onClick={(e) => { e.stopPropagation(); onSelect(''); }}
              aria-label={t('eventDiff.clearSelection')}
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}
        </div>

        {open && (
          <div className="absolute z-50 mt-1 w-full overflow-hidden rounded-lg border border-rail bg-popover shadow-elevated">
            <div className="border-b border-rail p-2">
              <div className="relative">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
                <Input
                  placeholder={t('eventDiff.searchPlaceholder')}
                  value={searchQuery}
                  onChange={(e) => onSearchChange(e.target.value)}
                  className="h-8 pl-8 text-xs"
                  autoFocus
                />
              </div>
            </div>

            <div className="max-h-64 overflow-y-auto">
              {loading ? (
                <div className="p-2"><SkeletonRows count={3} height="h-11" /></div>
              ) : error !== undefined ? (
                <ErrorState
                  error={error}
                  onRetry={onRetry}
                  className="flex flex-col items-center justify-center py-6"
                />
              ) : events.length === 0 ? (
                <EmptyState
                  icon={Search}
                  title={searchQuery ? t('eventDiff.noSearchResults') : t('eventDiff.noEvents')}
                  className="flex flex-col items-center justify-center py-6"
                />
              ) : (
                events.map((ev) => (
                  <button
                    key={ev.id}
                    type="button"
                    className={`w-full border-b border-rail px-3 py-2 text-left text-xs transition-colors last:border-b-0 hover:bg-secondary ${ev.id === selectedId ? 'bg-accent' : ''}`}
                    onClick={() => { onSelect(ev.id); setOpen(false); }}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate font-mono text-[13px] font-medium">{ev.eventType}</span>
                      <span className="flex-shrink-0 font-mono text-[11px] text-muted-foreground">
                        {formatRelativeTime(ev.createdAt)}
                      </span>
                    </div>
                    <div className="mt-0.5 flex items-center gap-2 font-mono text-[10px] text-muted-foreground">
                      <span>{ev.id.substring(0, 12)}</span>
                      <span>{formatDateTime(ev.createdAt)}</span>
                    </div>
                  </button>
                ))
              )}
            </div>

            {totalPages > 1 && (
              <div className="flex items-center justify-between border-t border-rail bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
                <span className="font-mono">
                  {t('eventDiff.eventsCount', { count: totalElements })} · {t('eventDiff.pageInfo', { current: currentPage + 1, total: totalPages })}
                </span>
                <span className="flex gap-1">
                  <Button variant="ghost" size="icon-sm" disabled={currentPage === 0} onClick={() => onPageChange(currentPage - 1)} title={t('common.previous')} aria-label={t('common.previous')}>
                    <ChevronLeft className="h-3.5 w-3.5" />
                  </Button>
                  <Button variant="ghost" size="icon-sm" disabled={currentPage >= totalPages - 1} onClick={() => onPageChange(currentPage + 1)} title={t('common.next')} aria-label={t('common.next')}>
                    <ChevronRight className="h-3.5 w-3.5" />
                  </Button>
                </span>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Page ────────────────────────────────────────────────────────────

export default function EventDiffPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();

  const [allEvents, setAllEvents] = useState<EventResponse[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [eventsPage, setEventsPage] = useState(0);
  const [loadingEvents, setLoadingEvents] = useState(true);
  const [eventsError, setEventsError] = useState<unknown>(undefined);
  const [searchQuery, setSearchQuery] = useState('');

  const [leftId, setLeftId] = useState(searchParams.get('left') || '');
  const [rightId, setRightId] = useState(searchParams.get('right') || '');

  const [diffResult, setDiffResult] = useState<EventDiffResponse | null>(null);
  const [diffing, setDiffing] = useState(false);
  const [sanitize, setSanitize] = useState(true);

  const loadEvents = useCallback(async (page: number) => {
    if (!projectId) return;
    try {
      setLoadingEvents(true);
      const data = await eventsApi.listByProject(projectId, { page, size: 20, sort: 'createdAt,desc' });
      setAllEvents(data.content);
      setTotalElements(data.totalElements);
      setTotalPages(data.totalPages);
      setEventsError(undefined);
    } catch (err: any) {
      setEventsError(err);
      showApiError(err, 'eventDiff.toast.loadEventsFailed');
    } finally {
      setLoadingEvents(false);
    }
  }, [projectId]);

  const runDiff = useCallback(async (left: string, right: string, mask: boolean) => {
    if (!projectId || !left || !right) return;
    try {
      setDiffing(true);
      const result = await eventDiffApi.diff(projectId, left, right, mask);
      setDiffResult(result);
      setSearchParams({ left, right });
    } catch (err: any) {
      showApiError(err, 'eventDiff.toast.diffFailed');
    } finally {
      setDiffing(false);
    }
  }, [projectId, setSearchParams]);

  useEffect(() => {
    if (projectId) loadEvents(eventsPage);
  }, [projectId, eventsPage, loadEvents]);

  // A link that already names both events compares itself once, on arrival.
  const deepLinked = useRef(false);
  useEffect(() => {
    if (deepLinked.current || !projectId) return;
    if (leftId && rightId && leftId !== rightId) {
      deepLinked.current = true;
      runDiff(leftId, rightId, sanitize);
    }
  }, [projectId, leftId, rightId, sanitize, runDiff]);

  const filteredEvents = useMemo(() => {
    if (!searchQuery.trim()) return allEvents;
    const q = searchQuery.toLowerCase();
    return allEvents.filter((ev) => ev.eventType.toLowerCase().includes(q) || ev.id.toLowerCase().includes(q));
  }, [allEvents, searchQuery]);

  const summary = useMemo(() => {
    if (!diffResult) return null;
    return {
      added: diffResult.diffs.filter((d) => d.type === 'ADDED').length,
      removed: diffResult.diffs.filter((d) => d.type === 'REMOVED').length,
      changed: diffResult.diffs.filter((d) => d.type === 'CHANGED').length,
      total: diffResult.diffs.length,
    };
  }, [diffResult]);

  if (loadingEvents && allEvents.length === 0) {
    return <PageSkeleton><div className="h-64 animate-pulse rounded-xl bg-muted" /></PageSkeleton>;
  }

  const sameEvent = !!leftId && leftId === rightId;

  const input = (
    <div className="space-y-4">
      <WorkbenchPanel
        eyebrow={t('eventDiff.inputEyebrow')}
        title={t('eventDiff.selectEvents')}
        description={t('eventDiff.selectDescription')}
        bodyClassName="space-y-4"
      >
        <EventPicker
          label={t('eventDiff.leftEvent')}
          events={filteredEvents}
          selectedId={leftId}
          onSelect={setLeftId}
          totalPages={totalPages}
          currentPage={eventsPage}
          onPageChange={setEventsPage}
          searchQuery={searchQuery}
          onSearchChange={setSearchQuery}
          loading={loadingEvents}
          error={eventsError}
          onRetry={() => loadEvents(eventsPage)}
          totalElements={totalElements}
        />
        <EventPicker
          label={t('eventDiff.rightEvent')}
          events={filteredEvents}
          selectedId={rightId}
          onSelect={setRightId}
          totalPages={totalPages}
          currentPage={eventsPage}
          onPageChange={setEventsPage}
          searchQuery={searchQuery}
          onSearchChange={setSearchQuery}
          loading={loadingEvents}
          error={eventsError}
          onRetry={() => loadEvents(eventsPage)}
          totalElements={totalElements}
        />

        <div className="flex items-center gap-3 rounded-lg border border-rail p-3">
          <Switch id="ed-sanitize" checked={sanitize} onCheckedChange={setSanitize} />
          <div>
            <Label htmlFor="ed-sanitize" className="cursor-pointer text-[13px]">{t('eventDiff.sanitizePii')}</Label>
            <p className="text-[11px] text-muted-foreground">{t('eventDiff.sanitizePiiHint')}</p>
          </div>
        </div>

        {sameEvent && (
          <p className="flex items-center gap-1.5 text-xs text-retry">
            <AlertTriangle className="h-3.5 w-3.5" aria-hidden />
            {t('eventDiff.sameEventWarning')}
          </p>
        )}
      </WorkbenchPanel>

      <RunControl
        icon={GitCompare}
        label={t('eventDiff.compare')}
        runningLabel={t('eventDiff.comparing')}
        running={diffing}
        disabled={!leftId || !rightId || sameEvent}
        onClick={() => runDiff(leftId, rightId, sanitize)}
        hint={t('eventDiff.compareHint')}
      />
    </div>
  );

  const result = !diffResult || !summary ? (
    <ResultPlaceholder
      icon={GitCompare}
      title={t('eventDiff.emptyTitle')}
      hint={t('eventDiff.emptyDescription')}
    />
  ) : (
    <ResultFrame
      // Two events that differ are not "broken", but a drift between them is
      // the thing a reader came to notice, so it reads as attention-needed.
      kind={summary.total === 0 ? 'ok' : 'retry'}
      statusLabel={summary.total === 0
        ? t('eventDiff.noDiffsTitle')
        : t('eventDiff.differencesDetected', { count: summary.total })}
      title={diffResult.eventType}
      metrics={
        <>
          <ResultMetric label={t('eventDiff.addedLabel')} value={summary.added} />
          <ResultMetric label={t('eventDiff.removedLabel')} value={summary.removed} />
          <ResultMetric label={t('eventDiff.changedLabel')} value={summary.changed} />
        </>
      }
    >
      <EventDiffView
        leftPayload={diffResult.leftPayload}
        rightPayload={diffResult.rightPayload}
        diffs={diffResult.diffs}
        leftLabel={formatDateTime(diffResult.leftCreatedAt)}
        rightLabel={formatDateTime(diffResult.rightCreatedAt)}
      />
    </ResultFrame>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader title={t('eventDiff.title')} description={t('eventDiff.subtitle')} />
      <Workbench input={input} result={result} />
    </div>
  );
}
