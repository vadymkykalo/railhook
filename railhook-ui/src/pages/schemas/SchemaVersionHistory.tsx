import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ArrowDownCircle, ArrowUpCircle, Check, ChevronRight, Copy, GitCompareArrows,
  History, Info, Loader2, Pencil, Plus, Trash2,
} from 'lucide-react';
import {
  useSchemaVersions, useCreateSchemaVersion, usePromoteSchema, useDeprecateSchema,
  useSchemaChanges, useProjectSchemaChanges, useDeleteEventType, useUpdateEventType,
} from '../../api/queries';
import { showApiError, showSuccess } from '../../lib/toast';
import { formatDate } from '../../lib/date';
import type {
  CompatibilityMode, EventTypeCatalogResponse, EventSchemaVersionResponse, SchemaChangeResponse,
} from '../../api/schemas.api';
import EmptyState, { ErrorState } from '../../components/EmptyState';
import { SkeletonRows } from '../../components/PageSkeleton';
import StatusBadge, { type StatusKind } from '../../components/StatusBadge';
import JsonEditor from '../../components/JsonEditor';
import { Button, buttonVariants } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Select } from '../../components/ui/select';
import {
  Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle,
} from '../../components/ui/dialog';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../../components/ui/alert-dialog';
import { cn } from '../../lib/utils';
import { formatJson } from '../../lib/json';

/**
 * One event type's history: every version it has had, and every diff between
 * them. A schema version is a contract, so its state is a domain status —
 * active is `ok`, a draft nobody has activated is `idle`, and a deprecated
 * version is `halt`.
 */

function kindOfVersionStatus(status: string): StatusKind {
  switch (status) {
    case 'ACTIVE': return 'ok';
    case 'DEPRECATED': return 'halt';
    default: return 'idle';
  }
}

function statusLabelKey(status: string): string {
  switch (status) {
    case 'ACTIVE': return 'schemas.status.ACTIVE';
    case 'DEPRECATED': return 'schemas.status.DEPRECATED';
    default: return 'schemas.status.DRAFT';
  }
}

const COMPATIBILITY_MODES: CompatibilityMode[] = ['NONE', 'BACKWARD', 'FORWARD', 'FULL'];

interface ChangeSummary {
  added: { path: string; type?: string; required?: boolean }[];
  removed: { path: string }[];
  changed: { path: string; oldType?: string; type?: string }[];
  /** Optional before, required now — the change BACKWARD refuses. */
  tightened: { path: string }[];
  /** Required before, optional now — the change FORWARD refuses. */
  relaxed: { path: string }[];
}

function parseChangeSummary(summary: string): ChangeSummary {
  try {
    const parsed = typeof summary === 'string' ? JSON.parse(summary) : summary;
    return {
      added: parsed.added || [],
      removed: parsed.removed || [],
      changed: parsed.changed || [],
      // Changes recorded before these two buckets existed have neither key.
      tightened: parsed.tightened || [],
      relaxed: parsed.relaxed || [],
    };
  } catch {
    return { added: [], removed: [], changed: [], tightened: [], relaxed: [] };
  }
}

/** A single +/−/~ tally, in the machine voice. */
function ChangeTally({ summary }: { summary: ChangeSummary }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-wrap items-center gap-2 font-mono text-[11px] text-muted-foreground">
      {summary.added.length > 0 && <span>{`+${summary.added.length} ${t('schemas.board.added')}`}</span>}
      {summary.removed.length > 0 && <span>{`−${summary.removed.length} ${t('schemas.board.removed')}`}</span>}
      {summary.changed.length > 0 && <span>{`~${summary.changed.length} ${t('schemas.board.changed')}`}</span>}
      {summary.tightened.length > 0 && <span>{`!${summary.tightened.length} ${t('schemas.board.tightened')}`}</span>}
      {summary.relaxed.length > 0 && <span>{`?${summary.relaxed.length} ${t('schemas.board.relaxed')}`}</span>}
    </div>
  );
}

function FieldList({ summary }: { summary: ChangeSummary }) {
  return (
    <div className="space-y-1.5">
      {[...summary.added.map((f) => ({ mark: '+', path: f.path, note: f.type })),
        ...summary.removed.map((f) => ({ mark: '−', path: f.path, note: undefined })),
        ...summary.changed.map((f) => ({ mark: '~', path: f.path, note: `${f.oldType} → ${f.type}` })),
        ...summary.tightened.map((f) => ({ mark: '!', path: f.path, note: undefined })),
        ...summary.relaxed.map((f) => ({ mark: '?', path: f.path, note: undefined }))]
        .map((entry, i) => (
          <div key={`${entry.path}-${i}`} className="flex items-baseline gap-2 font-mono text-[11px]">
            <span className="w-3 flex-shrink-0 text-muted-foreground">{entry.mark}</span>
            <span className="truncate">{entry.path}</span>
            {entry.note && <span className="text-muted-foreground">{entry.note}</span>}
          </div>
        ))}
    </div>
  );
}

export default function SchemaVersionHistory({
  projectId, eventType, onDeleted,
}: {
  projectId: string;
  eventType: EventTypeCatalogResponse;
  onDeleted: () => void;
}) {
  const { t } = useTranslation();
  const [tab, setTab] = useState<'versions' | 'changes'>('versions');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const deleteMutation = useDeleteEventType(projectId);
  const updateMutation = useUpdateEventType(projectId);
  const [editing, setEditing] = useState(false);
  const [editName, setEditName] = useState(eventType.name);
  const [editDesc, setEditDesc] = useState(eventType.description ?? '');

  const openEdit = () => {
    setEditName(eventType.name);
    setEditDesc(eventType.description ?? '');
    setEditing(true);
  };

  const handleUpdate = async () => {
    if (!editName.trim()) return;
    try {
      await updateMutation.mutateAsync({
        id: eventType.id,
        data: { name: editName.trim(), description: editDesc.trim() || undefined },
      });
      setEditing(false);
      showSuccess(t('schemas.editTypeSaved'));
    } catch (err) {
      showApiError(err, 'schemas.editTypeFailed');
    }
  };

  const handleDelete = async () => {
    try {
      await deleteMutation.mutateAsync(eventType.id);
      setConfirmDelete(false);
      onDeleted();
      showSuccess(t('schemas.eventTypeDeleted'));
    } catch (err: any) {
      showApiError(err, 'schemas.deleteFailed');
    }
  };

  return (
    <div className="space-y-4">
      <section className="rounded-xl border border-rail bg-card shadow-card">
        <header className="flex flex-wrap items-start justify-between gap-3 p-4">
          <div className="min-w-0">
            <div className="mono-label">{t('schemas.eventTypeEyebrow')}</div>
            <h3 className="truncate font-mono text-[15px] font-medium">{eventType.name}</h3>
            {eventType.description && (
              <p className="mt-1 text-sm text-muted-foreground">{eventType.description}</p>
            )}
            <div className="mt-2 flex flex-wrap items-center gap-3 text-[11px] text-muted-foreground">
              <span>{t('schemas.created')}</span>
              <span className="font-mono">{formatDate(eventType.createdAt)}</span>
              {eventType.latestVersion != null && (
                <>
                  <span>{t('schemas.latestVersion')}</span>
                  <span className="font-mono">v{eventType.latestVersion}</span>
                </>
              )}
            </div>
          </div>
          <div className="flex flex-shrink-0 items-center gap-1">
            <Button
              size="icon-sm"
              variant="ghost"
              className="text-muted-foreground"
              onClick={openEdit}
              title={t('schemas.editType')}
              aria-label={t('schemas.editType')}
            >
              <Pencil className="h-3.5 w-3.5" />
            </Button>
            <Button
              size="icon-sm"
              variant="ghost"
              className="text-muted-foreground hover:text-halt"
              onClick={() => setConfirmDelete(true)}
              title={t('schemas.deleteType')}
              aria-label={t('schemas.deleteType')}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </Button>
          </div>
        </header>

        <div className="flex gap-1 border-t border-rail px-3">
          <HistoryTab active={tab === 'versions'} onClick={() => setTab('versions')} label={t('schemas.versions')} />
          <HistoryTab active={tab === 'changes'} onClick={() => setTab('changes')} label={t('schemas.changeHistory')} />
        </div>
      </section>

      {tab === 'versions'
        ? <VersionList projectId={projectId} eventType={eventType} />
        : <ChangeList projectId={projectId} eventType={eventType} />}

      <Dialog open={editing} onOpenChange={setEditing}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('schemas.editType')}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="event-type-name">{t('schemas.typeName')}</Label>
              <Input
                id="event-type-name"
                value={editName}
                onChange={(e) => setEditName(e.target.value)}
                className="font-mono text-sm"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="event-type-description">{t('schemas.typeDescription')}</Label>
              <Input
                id="event-type-description"
                value={editDesc}
                onChange={(e) => setEditDesc(e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setEditing(false)}>{t('common.cancel')}</Button>
            <Button onClick={handleUpdate} disabled={!editName.trim() || updateMutation.isPending}>
              {updateMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('common.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('schemas.deleteType')}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('schemas.confirmDelete', { name: eventType.name })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteMutation.isPending}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              disabled={deleteMutation.isPending}
              className={buttonVariants({ variant: 'destructive' })}
            >
              {deleteMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('common.delete')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function HistoryTab({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        '-mb-px border-b-2 px-3 py-2 text-[13px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        active ? 'border-primary font-medium text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground',
      )}
    >
      {label}
    </button>
  );
}

// ── Versions ───────────────────────────────────────────────────────

function VersionList({ projectId, eventType }: { projectId: string; eventType: EventTypeCatalogResponse }) {
  const { t } = useTranslation();
  const {
    data: versions, isLoading, isError, error, refetch, isRefetching,
  } = useSchemaVersions(projectId, eventType.id);
  const createMutation = useCreateSchemaVersion(projectId, eventType.id);
  const promoteMutation = usePromoteSchema(projectId, eventType.id);
  const deprecateMutation = useDeprecateSchema(projectId, eventType.id);

  const [showUpload, setShowUpload] = useState(false);
  const [schemaInput, setSchemaInput] = useState('');
  const [versionDesc, setVersionDesc] = useState('');
  const [compatibility, setCompatibility] = useState<CompatibilityMode | ''>('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const handleUpload = async () => {
    try {
      JSON.parse(schemaInput);
    } catch {
      showApiError({ response: { data: { message: t('schemas.invalidJson') } } }, 'schemas.invalidJson');
      return;
    }
    try {
      // Left blank the field is omitted, and the server inherits the previous version's mode.
      // Sending NONE explicitly is a different thing: it drops the promise the type was under.
      await createMutation.mutateAsync({
        schemaJson: schemaInput,
        description: versionDesc.trim() || undefined,
        compatibilityMode: compatibility || undefined,
      });
      setSchemaInput('');
      setVersionDesc('');
      setCompatibility('');
      setShowUpload(false);
      showSuccess(t('schemas.versionCreated'));
    } catch (err: any) {
      showApiError(err, 'schemas.createVersionFailed');
    }
  };

  const run = async (fn: () => Promise<unknown>, successKey: string, failKey: string) => {
    try {
      await fn();
      showSuccess(t(successKey));
    } catch (err: any) {
      showApiError(err, failKey);
    }
  };

  const copySchema = (schema: string, id: string) => {
    navigator.clipboard.writeText(schema);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  return (
    <div className="space-y-3">
      <div className="flex justify-end">
        <Button size="sm" variant={showUpload ? 'secondary' : 'default'} onClick={() => setShowUpload(!showUpload)}>
          <Plus className="h-3.5 w-3.5" /> {t('schemas.uploadSchema')}
        </Button>
      </div>

      {showUpload && (
        <div className="space-y-3 rounded-xl border border-rail bg-card p-4 shadow-card">
          <div className="space-y-1.5">
            <Label className="text-xs">{t('schemas.jsonSchema')}</Label>
            <JsonEditor
              value={schemaInput}
              onChange={setSchemaInput}
              minHeight="200px"
              maxHeight="320px"
              placeholder='{"type": "object", "properties": {}}'
            />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="sv-note" className="text-xs">{t('schemas.versionNote')}</Label>
              <Input
                id="sv-note"
                value={versionDesc}
                onChange={(e) => setVersionDesc(e.target.value)}
                placeholder={t('schemas.versionNotePlaceholder')}
                className="h-8 text-sm"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sv-compat" className="text-xs">{t('schemas.compatibility')}</Label>
              <Select
                id="sv-compat"
                value={compatibility}
                onChange={(e) => setCompatibility(e.target.value as CompatibilityMode | '')}
                className="h-8 text-sm"
              >
                <option value="">{t('schemas.compatibilityInherit')}</option>
                {COMPATIBILITY_MODES.map((m) => <option key={m} value={m}>{m}</option>)}
              </Select>
              <p className="text-[11px] text-muted-foreground">{t('schemas.compatibilityHint')}</p>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" onClick={handleUpload} disabled={!schemaInput.trim() || createMutation.isPending}>
              {createMutation.isPending && <Loader2 className="h-3 w-3 animate-spin" />}
              {t('schemas.uploadVersion')}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => { setShowUpload(false); setSchemaInput(''); setVersionDesc(''); }}>
              {t('common.cancel')}
            </Button>
            <span className="ml-auto flex items-center gap-1.5 text-[11px] text-muted-foreground">
              <Info className="h-3 w-3" aria-hidden />
              {t('schemas.uploadHint')}
            </span>
          </div>
        </div>
      )}

      {isLoading ? (
        <SkeletonRows count={3} height="h-14" />
      ) : isError ? (
        <ErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      ) : !versions?.length ? (
        <EmptyState
          icon={History}
          title={t('schemas.noVersions')}
          description={t('schemas.noVersionsHint')}
          action={(
            <Button size="sm" onClick={() => setShowUpload(true)}>
              <Plus className="h-3.5 w-3.5" /> {t('schemas.uploadSchema')}
            </Button>
          )}
        />
      ) : (
        <ul className="space-y-2">
          {versions.map((v: EventSchemaVersionResponse) => {
            const isExpanded = expanded === v.id;
            return (
              <li key={v.id} className="overflow-hidden rounded-xl border border-rail bg-card shadow-card">
                <div className="flex flex-wrap items-center gap-2 p-3.5">
                  <button
                    type="button"
                    aria-expanded={isExpanded}
                    onClick={() => setExpanded(isExpanded ? null : v.id)}
                    className="flex min-w-0 flex-1 items-center gap-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    <ChevronRight className={cn('h-4 w-4 flex-shrink-0 text-muted-foreground transition-transform', isExpanded && 'rotate-90')} aria-hidden />
                    <span className="font-mono text-sm font-medium">v{v.version}</span>
                    <StatusBadge kind={kindOfVersionStatus(v.status)} label={t(statusLabelKey(v.status))} />
                    <span className="hidden truncate font-mono text-[11px] text-muted-foreground sm:inline">
                      {v.fingerprint.slice(0, 12)}
                    </span>
                    {v.compatibilityMode !== 'NONE' && (
                      <span className="hidden font-mono text-[11px] text-muted-foreground md:inline">
                        {v.compatibilityMode}
                      </span>
                    )}
                    <span className="ml-auto flex-shrink-0 font-mono text-[11px] text-muted-foreground">
                      {formatDate(v.createdAt)}
                    </span>
                  </button>
                  {v.status === 'DRAFT' && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => run(() => promoteMutation.mutateAsync(v.id), 'schemas.versionPromoted', 'schemas.promoteFailed')}
                      disabled={promoteMutation.isPending}
                    >
                      <ArrowUpCircle className="h-3 w-3" /> {t('schemas.promote')}
                    </Button>
                  )}
                  {v.status === 'ACTIVE' && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => run(() => deprecateMutation.mutateAsync(v.id), 'schemas.versionDeprecated', 'schemas.deprecateFailed')}
                      disabled={deprecateMutation.isPending}
                    >
                      <ArrowDownCircle className="h-3 w-3" /> {t('schemas.deprecate')}
                    </Button>
                  )}
                </div>

                {v.description && !isExpanded && (
                  <p className="truncate px-3.5 pb-3 text-xs text-muted-foreground">{v.description}</p>
                )}

                {isExpanded && (
                  <div className="space-y-3 border-t border-rail p-4">
                    {v.description && <p className="text-sm text-muted-foreground">{v.description}</p>}
                    <div className="flex items-center justify-between">
                      <span className="mono-label">{t('schemas.jsonSchema')}</span>
                      <Button size="sm" variant="ghost" onClick={() => copySchema(v.schemaJson, v.id)}>
                        {copiedId === v.id ? <Check className="h-3 w-3 text-ok" /> : <Copy className="h-3 w-3" />}
                        {copiedId === v.id ? t('common.copied') : t('schemas.copySchema')}
                      </Button>
                    </div>
                    <JsonEditor value={formatJson(v.schemaJson)} readOnly minHeight="160px" maxHeight="320px" />
                    <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                      <span className="mono-label">{t('schemas.fingerprint')}</span>
                      <code className="break-all font-mono">{v.fingerprint}</code>
                    </div>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

// ── Changes for one event type ─────────────────────────────────────

function ChangeList({ projectId, eventType }: { projectId: string; eventType: EventTypeCatalogResponse }) {
  const { t } = useTranslation();
  const {
    data: changes, isLoading, isError, error, refetch, isRefetching,
  } = useSchemaChanges(projectId, eventType.id);

  if (isLoading) return <SkeletonRows count={3} height="h-20" />;

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />;
  }

  if (!changes?.length) {
    return (
      <EmptyState
        icon={GitCompareArrows}
        title={t('schemas.noChanges')}
        description={t('schemas.noChangesHint')}
      />
    );
  }

  return (
    <ul className="space-y-2">
      {changes.map((c: SchemaChangeResponse) => {
        const summary = parseChangeSummary(c.changeSummary);
        return (
          <li key={c.id} className="rounded-xl border border-rail bg-card p-3.5 shadow-card">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm font-medium">
                {c.fromVersion != null ? `v${c.fromVersion} → v${c.toVersion}` : `v${c.toVersion}`}
              </span>
              {c.breaking && <StatusBadge kind="retry" label={t('schemas.breakingChange')} />}
              <span className="ml-auto font-mono text-[11px] text-muted-foreground">{formatDate(c.createdAt)}</span>
            </div>
            <div className="mt-2 space-y-2">
              <ChangeTally summary={summary} />
              <FieldList summary={summary} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}

// ── Project-wide timeline, shown when no event type is selected ────

export function RecentSchemaChanges({ projectId }: { projectId: string }) {
  const { t } = useTranslation();
  const { data: changes, isLoading } = useProjectSchemaChanges(projectId);
  const [expanded, setExpanded] = useState(false);

  if (isLoading || !changes?.length) return null;

  const breakingCount = changes.filter((c) => c.breaking).length;
  const shown = expanded ? changes : changes.slice(0, 6);

  return (
    <section className="rounded-xl border border-rail bg-card shadow-card">
      <header className="flex flex-wrap items-center gap-2 border-b border-rail px-4 py-2.5">
        <History className="h-4 w-4 text-muted-foreground" aria-hidden />
        <h3 className="text-[13px] font-medium">{t('schemas.board.title')}</h3>
        <span className="font-mono text-[11px] text-muted-foreground">{changes.length}</span>
        {breakingCount > 0 && (
          <StatusBadge kind="retry" label={t('schemas.board.breakingWithCount', { count: breakingCount })} />
        )}
      </header>
      <ul className="divide-y divide-rail">
        {shown.map((c) => {
          const summary = parseChangeSummary(c.changeSummary);
          return (
            <li key={c.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5">
              <span className="truncate font-mono text-xs font-medium">{c.eventTypeName ?? '—'}</span>
              <span className="font-mono text-[11px] text-muted-foreground">
                {c.fromVersion != null ? `v${c.fromVersion} → v${c.toVersion}` : `v${c.toVersion}`}
              </span>
              {c.breaking && <StatusBadge kind="retry" label={t('schemas.breakingChange')} icon={false} />}
              <ChangeTally summary={summary} />
              <span className="ml-auto font-mono text-[10px] text-muted-foreground">{formatDate(c.createdAt)}</span>
            </li>
          );
        })}
      </ul>
      {changes.length > shown.length || expanded ? (
        <div className="border-t border-rail px-4 py-2">
          <Button variant="ghost" size="sm" onClick={() => setExpanded(!expanded)}>
            <GitCompareArrows className="h-3.5 w-3.5" />
            {expanded ? t('schemas.board.showLess') : t('schemas.board.showAll', { count: changes.length })}
          </Button>
        </div>
      ) : null}
    </section>
  );
}

