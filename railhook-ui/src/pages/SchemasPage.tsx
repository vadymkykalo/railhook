import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { FileJson2 } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { useEventTypes } from '../api/queries';
import type { EventTypeCatalogResponse } from '../api/schemas.api';
import SchemaValidationPanel from './schemas/SchemaValidationPanel';
import SchemaListPanel from './schemas/SchemaListPanel';
import SchemaVersionHistory, { RecentSchemaChanges } from './schemas/SchemaVersionHistory';

/**
 * The schema registry, as three things rather than one 943-line file:
 *
 *   `SchemaListPanel`        — which event types have a contract
 *   `SchemaVersionHistory`   — what that contract has been, version by version
 *   `SchemaValidationPanel`  — what happens to an event that breaks it
 *
 * This file only decides which event type is selected and where the three sit.
 */
export default function SchemasPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const [selected, setSelected] = useState<EventTypeCatalogResponse | null>(null);
  const { data: eventTypes = [], isLoading, isError, error, refetch, isFetching } = useEventTypes(projectId);

  if (!projectId) return null;

  if (isLoading) {
    return (
      <PageSkeleton>
        <SkeletonCards count={1} height="h-36" cols="grid-cols-1" />
        <SkeletonCards count={2} height="h-80" cols="grid-cols-1 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]" />
      </PageSkeleton>
    );
  }

  // Without this the catalogue request failing falls through `data = []` and
  // draws "0 event types" over an empty list — a down backend wearing the face
  // of an empty project, which is the one thing EmptyState's own docblock says
  // never to do.
  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <PageHeader title={t('schemas.title')} description={t('schemas.subtitle')} />
        <ErrorState
          error={error}
          fallbackKey="schemas.loadFailed"
          onRetry={() => refetch()}
          retrying={isFetching}
        />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('schemas.typeCount', { count: eventTypes.length })}
        title={t('schemas.title')}
        description={t('schemas.subtitle')}
      />

      <div className="space-y-5">
        <SchemaValidationPanel projectId={projectId} />

        <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
          <SchemaListPanel projectId={projectId} selected={selected} onSelect={setSelected} />

          <div className="min-w-0 space-y-4">
            {selected ? (
              <SchemaVersionHistory
                projectId={projectId}
                eventType={selected}
                onDeleted={() => setSelected(null)}
              />
            ) : (
              <>
                <RecentSchemaChanges projectId={projectId} />
                <EmptyState
                  icon={FileJson2}
                  title={t('schemas.selectEventType')}
                  description={t('schemas.selectEventTypeHint')}
                  className="flex min-h-[240px] flex-col items-center justify-center rounded-xl border border-dashed border-rail px-6"
                />
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
