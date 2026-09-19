import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { FileJson2, Loader2, Plus, Search, X } from 'lucide-react';
import { useEventTypes, useCreateEventType } from '../../api/queries';
import { showApiError, showSuccess } from '../../lib/toast';
import type { EventTypeCatalogResponse } from '../../api/schemas.api';
import EmptyState, { ErrorState } from '../../components/EmptyState';
import { SkeletonRows } from '../../components/PageSkeleton';
import StatusBadge from '../../components/StatusBadge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { cn } from '../../lib/utils';

/**
 * The catalog: every event type this project has a contract for.
 *
 * One job — choose which event type you are looking at, or register a new one.
 * Versions, diffs and policy live elsewhere.
 */
export default function SchemaListPanel({
  projectId, selected, onSelect,
}: {
  projectId: string;
  selected: EventTypeCatalogResponse | null;
  onSelect: (eventType: EventTypeCatalogResponse | null) => void;
}) {
  const { t } = useTranslation();
  const {
    data: eventTypes, isLoading, isError, error, refetch, isRefetching,
  } = useEventTypes(projectId);
  const createMutation = useCreateEventType(projectId);

  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const all = eventTypes || [];
  const filtered = all.filter((et) => et.name.toLowerCase().includes(search.toLowerCase()));

  const handleCreate = async () => {
    if (!newName.trim()) return;
    try {
      const created = await createMutation.mutateAsync({
        name: newName.trim(),
        description: newDesc.trim() || undefined,
      });
      setNewName('');
      setNewDesc('');
      setShowCreate(false);
      onSelect(created);
      showSuccess(t('schemas.eventTypeCreated'));
    } catch (err: any) {
      showApiError(err, 'schemas.createFailed');
    }
  };

  return (
    <section className="rounded-xl border border-rail bg-card shadow-card">
      <header className="flex items-center justify-between gap-2 border-b border-rail px-4 py-2.5">
        <div>
          <div className="mono-label">{t('schemas.catalogEyebrow')}</div>
          <h3 className="text-[13px] font-medium">{t('schemas.eventTypes')}</h3>
        </div>
        <Button size="sm" variant={showCreate ? 'secondary' : 'outline'} onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? <X className="h-3.5 w-3.5" /> : <Plus className="h-3.5 w-3.5" />}
          {showCreate ? t('common.cancel') : t('schemas.addType')}
        </Button>
      </header>

      {showCreate && (
        <div className="space-y-3 border-b border-rail bg-muted/30 p-4">
          <div className="space-y-1.5">
            <Label htmlFor="sc-name" className="text-xs">{t('schemas.eventTypeName')}</Label>
            <Input
              id="sc-name"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder="order.created"
              className="h-8 font-mono text-sm"
            />
            <p className="text-[11px] text-muted-foreground">{t('schemas.eventTypeNameHint')}</p>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="sc-desc" className="text-xs">{t('schemas.description')}</Label>
            <Input
              id="sc-desc"
              value={newDesc}
              onChange={(e) => setNewDesc(e.target.value)}
              placeholder={t('schemas.descriptionPlaceholder')}
              className="h-8 text-sm"
            />
          </div>
          <Button size="sm" onClick={handleCreate} disabled={!newName.trim() || createMutation.isPending}>
            {createMutation.isPending && <Loader2 className="h-3 w-3 animate-spin" />}
            {t('common.create')}
          </Button>
        </div>
      )}

      {all.length > 0 && (
        <div className="border-b border-rail p-3">
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t('schemas.searchTypes')}
              className="h-8 pl-8 text-sm"
            />
          </div>
        </div>
      )}

      <div className="max-h-[560px] overflow-y-auto p-2">
        {isLoading ? (
          <SkeletonRows count={5} height="h-12" />
        ) : isError ? (
          <ErrorState
            className="flex flex-col items-center justify-center px-4 py-10 text-center"
            error={error}
            onRetry={() => refetch()}
            retrying={isRefetching}
          />
        ) : all.length === 0 ? (
          <EmptyState
            className="flex flex-col items-center justify-center px-4 py-10 text-center"
            icon={FileJson2}
            title={t('schemas.noEventTypes')}
            action={
              <Button size="sm" onClick={() => setShowCreate(true)}>
                <Plus className="h-3.5 w-3.5" /> {t('schemas.addFirstType')}
              </Button>
            }
          />
        ) : filtered.length === 0 ? (
          <p className="px-3 py-8 text-center text-sm text-muted-foreground">{t('schemas.noSearchResults')}</p>
        ) : (
          <ul className="space-y-0.5">
            {filtered.map((et) => (
              <li key={et.id}>
                <button
                  type="button"
                  aria-current={selected?.id === et.id}
                  onClick={() => onSelect(et)}
                  className={cn(
                    'w-full rounded-lg border px-3 py-2 text-left transition-colors',
                    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                    selected?.id === et.id
                      ? 'border-primary/30 bg-accent'
                      : 'border-transparent hover:bg-secondary',
                  )}
                >
                  <div className="truncate font-mono text-[13px] font-medium">{et.name}</div>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    {et.latestVersion != null && (
                      <span className="font-mono text-[11px] text-muted-foreground">v{et.latestVersion}</span>
                    )}
                    {et.activeVersionStatus === 'ACTIVE' && (
                      <StatusBadge kind="ok" label={t('schemas.status.ACTIVE')} icon={false} />
                    )}
                    {et.hasBreakingChanges && (
                      <StatusBadge kind="retry" label={t('schemas.breakingChange')} icon={false} />
                    )}
                  </div>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
