import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, History, Loader2, RotateCcw, Eye, GitCompare } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import PageSkeleton from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import EventDiffView from '../components/EventDiffView';
import JsonEditor from '../components/JsonEditor';
import { Badge } from '../components/ui/badge';
import { Button, buttonVariants } from '../components/ui/button';
import { Card } from '../components/ui/card';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { usePermissions } from '../auth/usePermissions';
import {
  useTransformation,
  useTransformationVersions,
  useTransformationVersion,
  useTransformationVersionDiff,
  useRestoreTransformationVersion,
} from '../api/queries';
import { formatJson } from '../lib/json';
import type { TransformationVersionResponse } from '../types/api.types';

/**
 * Every template this transformation has published, and what to do with them.
 *
 * <p>Restoring is deliberately not "undo": it publishes the old template again as the next
 * version and leaves everything after it in place, so the confirmation says which version the
 * restore will become rather than implying the ones in between go away.
 */
export default function TransformationHistoryPage() {
  const { t } = useTranslation();
  const { projectId, transformationId } = useParams<{ projectId: string; transformationId: string }>();
  const { canManageSubscriptions: canManage } = usePermissions();

  const transformationQuery = useTransformation(projectId!, transformationId!);
  const versionsQuery = useTransformationVersions(projectId!, transformationId!);

  /** At most two, in the order they were picked; a third replaces the older one. */
  const [compare, setCompare] = useState<number[]>([]);
  const [viewing, setViewing] = useState<number | null>(null);
  const [restoring, setRestoring] = useState<number | null>(null);

  const [left, right] = compare.length === 2
    ? [Math.min(compare[0], compare[1]), Math.max(compare[0], compare[1])]
    : [null, null];

  const diffQuery = useTransformationVersionDiff(projectId!, transformationId!, left, right);
  const viewedQuery = useTransformationVersion(projectId!, transformationId!, viewing);
  const restoreMutation = useRestoreTransformationVersion(projectId!, transformationId!);

  const toggleCompare = (version: number) => {
    setCompare((current) => {
      if (current.includes(version)) return current.filter((v) => v !== version);
      return current.length < 2 ? [...current, version] : [current[1], version];
    });
  };

  const handleRestore = () => {
    if (restoring === null) return;
    restoreMutation.mutate(restoring, {
      onSuccess: (updated) => {
        showSuccess(t('transformationHistory.toast.restored', { from: restoring, to: updated.version }));
        setRestoring(null);
        setCompare([]);
      },
      onError: (err) => showApiError(err, t('transformationHistory.toast.restoreFailed')),
    });
  };

  const authorOf = (version: TransformationVersionResponse) => {
    if (version.createdByEmail) return version.createdByEmail;
    if (version.createdBy) return t('transformationHistory.authorRemoved');
    return t('transformationHistory.authorNotAPerson');
  };

  const transformation = transformationQuery.data;
  const versions = versionsQuery.data ?? [];
  const backLink = `/projects/${projectId}/transformations`;

  if (transformationQuery.isLoading || versionsQuery.isLoading) return <PageSkeleton />;

  if (transformationQuery.isError || versionsQuery.isError || !transformation) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState
          error={transformationQuery.error ?? versionsQuery.error}
          onRetry={() => { transformationQuery.refetch(); versionsQuery.refetch(); }}
          retrying={transformationQuery.isRefetching || versionsQuery.isRefetching}
        />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <Link
        to={backLink}
        className="mb-3 inline-flex items-center gap-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
      >
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden />
        {t('transformationHistory.backToList')}
      </Link>

      <PageHeader
        eyebrow={transformation.name}
        title={t('transformationHistory.title')}
        description={t('transformationHistory.subtitle')}
      />

      {versions.length === 0 ? (
        <EmptyState
          icon={History}
          title={t('transformationHistory.empty')}
          description={t('transformationHistory.emptyDesc')}
        />
      ) : (
        <>
          <Card className="divide-y divide-rail overflow-hidden">
            {versions.map((version) => {
              const selected = compare.includes(version.version);
              return (
                <div
                  key={version.id}
                  className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3"
                  data-state={selected ? 'selected' : undefined}
                >
                  <label className="flex cursor-pointer items-center gap-2.5">
                    <input
                      type="checkbox"
                      className="h-4 w-4 cursor-pointer rounded border-rail accent-primary"
                      aria-label={t('transformationHistory.compareOne', { version: version.version })}
                      checked={selected}
                      onChange={() => toggleCompare(version.version)}
                    />
                    <span className="font-mono text-sm font-medium">v{version.version}</span>
                  </label>

                  {version.current && (
                    <Badge variant="ok">{t('transformationHistory.current')}</Badge>
                  )}
                  {version.restoredFromVersion != null && (
                    <Badge variant="outline">
                      {t('transformationHistory.restoredFrom', { version: version.restoredFromVersion })}
                    </Badge>
                  )}

                  <span className="min-w-0 truncate text-[13px] text-muted-foreground">
                    {authorOf(version)}
                  </span>

                  <span
                    className="font-mono text-xs text-muted-foreground"
                    title={formatDateTime(version.createdAt)}
                  >
                    {formatRelativeTime(version.createdAt)}
                  </span>

                  <div className="ml-auto flex gap-1">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setViewing(version.version)}
                      title={t('transformationHistory.view')}
                    >
                      <Eye className="h-3.5 w-3.5" /> {t('transformationHistory.view')}
                    </Button>
                    {!version.current && (
                      <PermissionGate allowed={canManage}>
                        <VerificationGate>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => setRestoring(version.version)}
                            title={t('transformationHistory.restore')}
                          >
                            <RotateCcw className="h-3.5 w-3.5" /> {t('transformationHistory.restore')}
                          </Button>
                        </VerificationGate>
                      </PermissionGate>
                    )}
                  </div>
                </div>
              );
            })}
          </Card>

          <div className="mt-6">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <GitCompare className="h-4 w-4 text-muted-foreground" aria-hidden />
              <h3 className="text-sm font-medium">{t('transformationHistory.comparison')}</h3>
              {compare.length > 0 && (
                <Button variant="ghost" size="sm" onClick={() => setCompare([])}>
                  {t('transformationHistory.clearComparison')}
                </Button>
              )}
            </div>

            {left === null || right === null ? (
              <Card className="p-6">
                <p className="text-center text-[13px] text-muted-foreground">
                  {t('transformationHistory.comparisonHint')}
                </p>
              </Card>
            ) : diffQuery.isLoading ? (
              <Card className="flex items-center justify-center p-6">
                <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" aria-hidden />
              </Card>
            ) : diffQuery.isError || !diffQuery.data ? (
              <ErrorState error={diffQuery.error} onRetry={() => diffQuery.refetch()} />
            ) : (
              <EventDiffView
                leftPayload={diffQuery.data.leftTemplate}
                rightPayload={diffQuery.data.rightTemplate}
                diffs={diffQuery.data.diffs}
                leftLabel={t('transformationHistory.sideLabel', {
                  version: diffQuery.data.leftVersion,
                  when: formatDateTime(diffQuery.data.leftCreatedAt),
                })}
                rightLabel={t('transformationHistory.sideLabel', {
                  version: diffQuery.data.rightVersion,
                  when: formatDateTime(diffQuery.data.rightCreatedAt),
                })}
              />
            )}
          </div>
        </>
      )}

      {/* One version, whole */}
      <Dialog open={viewing !== null} onOpenChange={(open) => !open && setViewing(null)}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t('transformationHistory.viewTitle', { version: viewing })}</DialogTitle>
            <DialogDescription>
              {viewedQuery.data
                ? t('transformationHistory.viewDesc', {
                  when: formatDateTime(viewedQuery.data.createdAt),
                  author: authorOf(viewedQuery.data),
                })
                : t('transformationHistory.viewLoading')}
            </DialogDescription>
          </DialogHeader>
          {viewedQuery.isLoading ? (
            <div className="flex justify-center py-8">
              <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" aria-hidden />
            </div>
          ) : viewedQuery.data?.template ? (
            <JsonEditor value={formatJson(viewedQuery.data.template)} readOnly minHeight="220px" maxHeight="420px" />
          ) : (
            <ErrorState error={viewedQuery.error} onRetry={() => viewedQuery.refetch()} />
          )}
        </DialogContent>
      </Dialog>

      {/* Restore */}
      <AlertDialog open={restoring !== null} onOpenChange={(open) => !open && setRestoring(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t('transformationHistory.restoreDialog.title', { version: restoring })}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {t('transformationHistory.restoreDialog.description', {
                version: restoring,
                next: (transformation.version ?? 0) + 1,
              })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={restoreMutation.isPending}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleRestore}
              disabled={restoreMutation.isPending}
              className={buttonVariants()}
            >
              {restoreMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('transformationHistory.restore')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
