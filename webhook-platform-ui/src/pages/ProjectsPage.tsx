import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, FolderKanban, Loader2, Trash2, Copy, Webhook, Send, Radio, Key, CreditCard, ShoppingCart, Github, Zap } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showCriticalSuccess } from '../lib/toast';
import { useProjects, useCreateProject, useDeleteProject } from '../api/queries';
import { dashboardApi, type DashboardStats } from '../api/dashboard.api';
import { formatDate, formatRelativeTime } from '../lib/date';
import { cn } from '../lib/utils';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import StatusBadge, { type StatusKind } from '../components/StatusBadge';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { Card, CardContent } from '../components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/ui/dialog';
import DangerConfirmDialog from '../components/DangerConfirmDialog';

type ProjectTemplate = 'custom' | 'stripe' | 'shopify' | 'github';

const TEMPLATES: { key: ProjectTemplate; icon: React.ElementType }[] = [
  { key: 'custom', icon: Zap },
  { key: 'stripe', icon: CreditCard },
  { key: 'shopify', icon: ShoppingCart },
  { key: 'github', icon: Github },
];

const TEMPLATE_DEFAULTS: Record<ProjectTemplate, { name: string; description: string }> = {
  custom: { name: '', description: '' },
  stripe: { name: 'Stripe Payments', description: 'Payment webhook integration — charge.succeeded, invoice.paid, refund.created' },
  shopify: { name: 'Shopify Store', description: 'E-commerce webhook integration — order.created, product.updated, checkout.completed' },
  github: { name: 'GitHub CI/CD', description: 'Repository webhook integration — push, pull_request.opened, workflow.completed' },
};

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** A project's delivery record, read on the same four meanings as a delivery itself. */
function kindOfSuccessRate(rate: number): StatusKind {
  if (rate >= 95) return 'ok';
  if (rate >= 80) return 'retry';
  return 'halt';
}

export default function ProjectsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data: projects = [], isLoading, isError, error, refetch, isRefetching } = useProjects();
  const createProject = useCreateProject();
  const deleteProject = useDeleteProject();
  const { canCreateProject, canDeleteProject } = usePermissions();

  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [selectedTemplate, setSelectedTemplate] = useState<ProjectTemplate>('custom');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [healthStats, setHealthStats] = useState<Record<string, DashboardStats>>({});

  const creating = createProject.isPending;
  const deleting = deleteProject.isPending;

  useEffect(() => {
    projects.forEach((project) => {
      if (!healthStats[project.id]) {
        dashboardApi.getProjectStats(project.id)
          .then((stats) => setHealthStats((prev) => ({ ...prev, [project.id]: stats })))
          .catch(() => { /* health is best-effort — a project card without it still works */ });
      }
    });
  }, [projects]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    createProject.mutate({ name, description }, {
      onSuccess: (project) => {
        setShowCreateDialog(false);
        setName('');
        setDescription('');
        showSuccess(t('projects.toast.created'));
        navigate(`/admin/projects/${project.id}/connection-setup`);
      },
      onError: (err: any) => showApiError(err, 'projects.toast.createFailed'),
    });
  };

  const handleDelete = () => {
    if (!deleteId) return;
    deleteProject.mutate(deleteId, {
      onSuccess: () => {
        showCriticalSuccess(t('projects.toast.deleted'));
        setDeleteId(null);
      },
      onError: (err: any) => showApiError(err, 'projects.toast.deleteFailed'),
    });
  };

  const handleCopyId = (id: string) => {
    navigator.clipboard.writeText(id);
    showSuccess(t('projects.toast.idCopied'));
  };

  if (isLoading) {
    return (
      <PageSkeleton>
        <SkeletonCards count={3} height="h-48" />
      </PageSkeleton>
    );
  }

  const newProjectButton = (
    <PermissionGate allowed={canCreateProject}>
      <VerificationGate>
        <Button onClick={() => setShowCreateDialog(true)}>
          <Plus className="h-4 w-4" aria-hidden />
          {t('projects.newProject')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={projects.length > 0 ? t('projects.count', { count: projects.length }) : undefined}
        title={t('projects.title')}
        description={t('projects.subtitle')}
        actions={newProjectButton}
      />

      {isError ? (
        <ErrorState error={error} fallbackKey="projects.loadFailed" onRetry={() => refetch()} retrying={isRefetching} />
      ) : projects.length === 0 ? (
        <EmptyState
          icon={FolderKanban}
          title={t('projects.noProjects')}
          description={canCreateProject ? t('projects.noProjectsDesc') : t('projects.noProjectsViewer')}
          action={
            <PermissionGate allowed={canCreateProject}>
              <VerificationGate>
                <Button onClick={() => setShowCreateDialog(true)}>
                  <Plus className="h-4 w-4" aria-hidden />
                  {t('projects.createFirst')}
                </Button>
              </VerificationGate>
            </PermissionGate>
          }
          docsLink="/docs#getting-started"
        />
      ) : (
        <div className="grid animate-fade-in gap-4 md:grid-cols-2 xl:grid-cols-3">
          {projects.map((project) => {
            const stats = healthStats[project.id];
            const ds = stats?.deliveryStats;
            const lastEvent = stats?.recentEvents?.[0];
            const endpointCount = stats?.endpointHealth?.length ?? 0;
            return (
              <Card key={project.id} className="flex flex-col">
                <CardContent className="flex flex-1 flex-col p-5">
                  <div className="flex items-start justify-between gap-2">
                    <button
                      onClick={() => navigate(`/admin/projects/${project.id}/endpoints`)}
                      className="min-w-0 flex-1 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                    >
                      <span className="block truncate text-[15px] font-medium hover:text-primary">{project.name}</span>
                      <span className="mono-label mt-0.5 block">{t('projects.created', { date: formatDate(project.createdAt) })}</span>
                    </button>
                    <div className="flex flex-shrink-0 gap-0.5">
                      <Button
                        variant="ghost" size="icon-sm"
                        onClick={() => handleCopyId(project.id)}
                        title={t('common.copyId')} aria-label={t('projects.copyIdOf', { name: project.name })}
                      >
                        <Copy className="h-3.5 w-3.5" />
                      </Button>
                      {canDeleteProject && (
                        <Button
                          variant="ghost" size="icon-sm"
                          onClick={() => setDeleteId(project.id)}
                          title={t('common.delete')} aria-label={t('projects.deleteNamed', { name: project.name })}
                          className="text-muted-foreground hover:text-halt"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      )}
                    </div>
                  </div>

                  {project.description && (
                    <p className="mt-2 line-clamp-2 text-[13px] text-muted-foreground">{project.description}</p>
                  )}

                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    {ds && ds.totalDeliveries > 0 ? (
                      <StatusBadge
                        kind={kindOfSuccessRate(ds.successRate)}
                        label={t('projects.health.successRate', { rate: Math.round(ds.successRate) })}
                      />
                    ) : (
                      <StatusBadge kind="idle" label={t('projects.health.noDeliveries')} />
                    )}
                    <span className="font-mono text-[11px] text-muted-foreground">
                      {endpointCount > 0 ? t('projects.health.endpoints', { count: endpointCount }) : t('projects.health.noEndpoints')}
                    </span>
                    <span className="font-mono text-[11px] text-muted-foreground">
                      {lastEvent ? t('projects.health.lastEvent', { time: formatRelativeTime(lastEvent.createdAt) }) : t('projects.health.noEvents')}
                    </span>
                  </div>

                  <div className="mt-auto flex flex-wrap gap-1.5 pt-4">
                    {[
                      { label: t('projects.quickLinks.endpoints'), path: `/admin/projects/${project.id}/endpoints`, icon: Webhook },
                      { label: t('projects.quickLinks.events'), path: `/admin/projects/${project.id}/events`, icon: Radio },
                      { label: t('projects.quickLinks.keys'), path: `/admin/projects/${project.id}/api-keys`, icon: Key },
                      { label: t('projects.quickLinks.deliveries'), path: `/admin/projects/${project.id}/deliveries`, icon: Send },
                    ].map((action) => (
                      <button
                        key={action.label}
                        onClick={() => navigate(action.path)}
                        className="inline-flex items-center gap-1 rounded-md border border-rail px-2 py-1 text-[11px] text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        <action.icon className="h-3 w-3" aria-hidden />
                        {action.label}
                      </button>
                    ))}
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      <Dialog
        open={showCreateDialog}
        onOpenChange={(open) => {
          setShowCreateDialog(open);
          if (!open) { setSelectedTemplate('custom'); setName(''); setDescription(''); }
        }}
      >
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{t('projects.createDialog.title')}</DialogTitle>
            <DialogDescription>{t('projects.createDialog.description')}</DialogDescription>
          </DialogHeader>
          <form onSubmit={handleCreate}>
            <div className="space-y-5 py-4">
              <div className="space-y-2">
                <span className="text-sm font-medium leading-none">{t('projects.createDialog.templateLabel')}</span>
                <div role="radiogroup" aria-label={t('projects.createDialog.templateLabel')} className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {TEMPLATES.map(({ key, icon: TIcon }) => (
                    <button
                      key={key}
                      type="button"
                      role="radio"
                      aria-checked={selectedTemplate === key}
                      onClick={() => {
                        setSelectedTemplate(key);
                        setName(TEMPLATE_DEFAULTS[key].name);
                        setDescription(TEMPLATE_DEFAULTS[key].description);
                      }}
                      className={cn(
                        'flex flex-col items-center gap-1.5 rounded-lg border p-3 text-center transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
                        selectedTemplate === key ? 'border-primary bg-accent/40' : 'border-rail hover:border-primary/40'
                      )}
                    >
                      <TIcon className={cn('h-4 w-4', selectedTemplate === key ? 'text-primary' : 'text-muted-foreground')} aria-hidden />
                      <span className="text-[11px] font-medium leading-tight">
                        {t(`projects.createDialog.template${capitalize(key)}`)}
                      </span>
                    </button>
                  ))}
                </div>
                <p className="text-xs text-muted-foreground">
                  {t(`projects.createDialog.template${capitalize(selectedTemplate)}Desc`)}
                </p>
              </div>

              <div className="space-y-2">
                <Label htmlFor="project-name">{t('projects.createDialog.name')}</Label>
                <Input
                  id="project-name"
                  placeholder={t('projects.createDialog.namePlaceholder')}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  disabled={creating}
                  autoFocus
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="project-description">{t('projects.createDialog.descriptionLabel')}</Label>
                <Textarea
                  id="project-description"
                  placeholder={t('projects.createDialog.descriptionPlaceholder')}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  disabled={creating}
                  rows={3}
                />
              </div>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setShowCreateDialog(false)} disabled={creating}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" disabled={creating}>
                {creating && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
                {creating ? t('projects.createDialog.submitting') : t('projects.createDialog.submit')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <DangerConfirmDialog
        open={!!deleteId}
        onOpenChange={(open) => !open && setDeleteId(null)}
        title={t('projects.deleteDialog.title', { name: projects.find((p) => p.id === deleteId)?.name ?? '' })}
        description={t('projects.deleteDialog.description')}
        confirmName={projects.find((p) => p.id === deleteId)?.name || ''}
        impact={[
          t('projects.deleteDialog.impactEndpoints'),
          t('projects.deleteDialog.impactEvents'),
          t('projects.deleteDialog.impactKeys'),
        ]}
        onConfirm={handleDelete}
        loading={deleting}
        confirmLabel={t('projects.deleteDialog.confirm')}
      />
    </div>
  );
}
