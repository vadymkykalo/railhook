import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2, Pencil, Play, GitBranch, CheckCircle2, XCircle } from 'lucide-react';
import { workflowsApi, type WorkflowResponse, type WorkflowRequest } from '../api/workflows.api';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { EnabledBadge } from '../components/StatusBadge';
import { formatDateTime } from '../lib/date';
import { showApiError, showSuccess } from '../lib/toast';

export default function WorkflowsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const { data: workflows = [], isLoading, isError, error, refetch, isFetching } = useQuery({
    queryKey: ['workflows', projectId],
    queryFn: () => workflowsApi.list(projectId!),
    enabled: !!projectId,
  });

  const createMutation = useMutation({
    mutationFn: (data: WorkflowRequest) => workflowsApi.create(projectId!, data),
    onSuccess: (wf) => {
      qc.invalidateQueries({ queryKey: ['workflows', projectId] });
      showSuccess(t('workflows.toast.created'));
      setShowCreate(false);
      setNewName('');
      setNewDesc('');
      navigate(`/admin/projects/${projectId}/workflows/${wf.id}`);
    },
    onError: (err) => showApiError(err, t('workflows.toast.createFailed')),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => workflowsApi.delete(projectId!, id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows', projectId] });
      showSuccess(t('workflows.toast.deleted'));
    },
    onError: (err) => showApiError(err, t('workflows.toast.deleteFailed')),
  });

  const toggleMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => workflowsApi.toggle(projectId!, id, enabled),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows', projectId] });
    },
    onError: (err) => showApiError(err, t('workflows.toast.toggleFailed')),
  });

  const handleCreate = () => {
    if (!newName.trim()) return;
    createMutation.mutate({
      name: newName.trim(),
      description: newDesc.trim() || undefined,
      triggerType: 'WEBHOOK_EVENT',
      definition: { nodes: [], edges: [] },
    });
  };

  const cancelCreate = () => {
    setShowCreate(false);
    setNewName('');
    setNewDesc('');
  };

  if (!projectId) return null;

  if (isLoading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={4} height="h-20" />
      </PageSkeleton>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        title={t('workflows.title')}
        description={t('workflows.subtitle')}
        actions={
          <Button onClick={() => setShowCreate(true)}>
            <Plus className="h-4 w-4" aria-hidden />
            {t('workflows.newWorkflow')}
          </Button>
        }
      />

      {showCreate && (
        <div className="animate-scale-in mb-5 rounded-lg border border-rail bg-card p-4">
          <h3 className="mb-3 text-[15px] font-medium">{t('workflows.createWorkflow')}</h3>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="workflow-name">{t('workflows.nameLabel')}</Label>
              <Input
                id="workflow-name"
                autoFocus
                placeholder={t('workflows.namePlaceholder')}
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="workflow-description">{t('workflows.descriptionLabel')}</Label>
              <Input
                id="workflow-description"
                placeholder={t('workflows.descriptionPlaceholder')}
                value={newDesc}
                onChange={(e) => setNewDesc(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
              />
            </div>
          </div>
          <div className="mt-4 flex gap-2">
            <Button onClick={handleCreate} disabled={!newName.trim() || createMutation.isPending} size="sm">
              {t('workflows.create')}
            </Button>
            <Button variant="ghost" size="sm" onClick={cancelCreate}>
              {t('workflows.cancel')}
            </Button>
          </div>
        </div>
      )}

      {isError ? (
        <ErrorState error={error} onRetry={() => refetch()} retrying={isFetching} />
      ) : workflows.length === 0 ? (
        <EmptyState
          icon={GitBranch}
          title={t('workflows.noWorkflows')}
          description={t('workflows.noWorkflowsHint')}
          action={
            <Button onClick={() => setShowCreate(true)}>
              <Plus className="h-4 w-4" aria-hidden /> {t('workflows.create')}
            </Button>
          }
        />
      ) : (
        <div className="divide-y divide-rail overflow-hidden rounded-lg border border-rail bg-card">
          {workflows.map((wf: WorkflowResponse) => (
            <div
              key={wf.id}
              role="button"
              tabIndex={0}
              onClick={() => navigate(`/admin/projects/${projectId}/workflows/${wf.id}`)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  navigate(`/admin/projects/${projectId}/workflows/${wf.id}`);
                }
              }}
              className="flex cursor-pointer flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3.5 transition-colors hover:bg-secondary/40"
            >
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate text-sm font-medium">{wf.name}</span>
                  <span className="font-mono text-[11px] text-muted-foreground">
                    {t('workflows.version', { version: wf.version })}
                  </span>
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                  <span className="font-mono">{t(`workflows.triggerTypes.${wf.triggerType}`)}</span>
                  <span className="font-mono">{t('workflows.builder.nodesCount', { count: wf.definition?.nodes?.length || 0 })}</span>
                  <span>{t('workflows.updated', { date: formatDateTime(wf.updatedAt) })}</span>
                </div>
                {wf.description && (
                  <p className="mt-1 truncate text-sm text-muted-foreground">{wf.description}</p>
                )}
              </div>

              <div className="hidden items-center gap-3 font-mono text-xs sm:flex">
                <span className="flex items-center gap-1 text-muted-foreground" title={t('workflows.totalExecutions')}>
                  <Play className="h-3 w-3" aria-hidden /> {wf.totalExecutions}
                </span>
                <span className="flex items-center gap-1 text-ok" title={t('workflows.successful')}>
                  <CheckCircle2 className="h-3 w-3" aria-hidden /> {wf.successfulExecutions}
                </span>
                <span className="flex items-center gap-1 text-halt" title={t('workflows.failed')}>
                  <XCircle className="h-3 w-3" aria-hidden /> {wf.failedExecutions}
                </span>
              </div>

              <EnabledBadge enabled={wf.enabled} />

              <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
                <Button
                  variant="ghost"
                  size="sm"
                  title={wf.enabled ? t('workflows.disable') : t('workflows.enable')}
                  onClick={() => toggleMutation.mutate({ id: wf.id, enabled: !wf.enabled })}
                >
                  {wf.enabled ? t('workflows.disable') : t('workflows.enable')}
                </Button>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  title={t('workflows.edit')}
                  aria-label={t('workflows.edit')}
                  onClick={() => navigate(`/admin/projects/${projectId}/workflows/${wf.id}`)}
                >
                  <Pencil className="h-3.5 w-3.5" aria-hidden />
                </Button>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="text-halt hover:text-halt"
                  title={t('workflows.delete')}
                  aria-label={t('workflows.delete')}
                  onClick={() => { if (confirm(t('workflows.deleteConfirm'))) deleteMutation.mutate(wf.id); }}
                >
                  <Trash2 className="h-3.5 w-3.5" aria-hidden />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
