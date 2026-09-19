import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Fingerprint } from 'lucide-react';
import { useProject, useUpdateProject } from '../api/queries';
import { usePermissions } from '../auth/usePermissions';
import { showApiError, showSuccess } from '../lib/toast';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import { ErrorState } from '../components/EmptyState';
import PermissionGate from '../components/PermissionGate';
import SegmentedChoice, { PolicyRow } from '../components/SegmentedChoice';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { FormSection, SaveControl } from './SettingsPage';

type IdempotencyChoice = 'NONE' | 'AUTO' | 'REQUIRED';

const IDEMPOTENCY_CHOICES: IdempotencyChoice[] = ['NONE', 'AUTO', 'REQUIRED'];

/**
 * The project's own settings: what it is called, and what it does with an event sent twice.
 *
 * Every update restates the name — the API requires it — and nothing else: the schema-validation
 * settings are left out, because the API keeps a field it is not sent, and a stale copy sent back
 * from this page would quietly undo a change made on the Schemas page.
 */
export default function ProjectSettingsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canEditProject } = usePermissions();
  const {
    data: project, isLoading, isError, error, refetch, isRefetching,
  } = useProject(projectId);
  const updateProject = useUpdateProject(projectId!);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (!project) return;
    setName(project.name);
    setDescription(project.description ?? '');
  }, [project]);

  if (isLoading) {
    return (
      <PageSkeleton maxWidth="max-w-4xl">
        <SkeletonCards count={2} height="h-44" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  if (isError || !project) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      </div>
    );
  }

  const dirty = name.trim() !== project.name || description.trim() !== (project.description ?? '');

  const saveDetails = async () => {
    try {
      await updateProject.mutateAsync({ name: name.trim(), description: description.trim() || undefined });
      setSaved(true);
      showSuccess(t('projectSettings.saved'));
    } catch (err) {
      showApiError(err, 'projectSettings.saveFailed');
    }
  };

  const idempotency = (IDEMPOTENCY_CHOICES as string[]).includes(project.idempotencyPolicy)
    ? project.idempotencyPolicy as IdempotencyChoice
    : 'NONE';

  const setIdempotency = async (policy: IdempotencyChoice) => {
    try {
      await updateProject.mutateAsync({
        name: project.name,
        description: project.description,
        idempotencyPolicy: policy,
      });
      showSuccess(t('projectSettings.idempotency.saved'));
    } catch (err) {
      showApiError(err, 'projectSettings.idempotency.saveFailed');
    }
  };

  return (
    <div className="p-4 lg:p-6">
      <div className="max-w-4xl">
        <PageHeader
          eyebrow={project.name}
          title={t('projectSettings.title')}
          description={t('projectSettings.subtitle')}
        />

        <div className="space-y-8">
          <FormSection
            title={t('projectSettings.details')}
            description={t('projectSettings.detailsDesc')}
            footer={
              <PermissionGate allowed={canEditProject}>
                <SaveControl
                  label={t('common.save')}
                  savingLabel={t('common.saving')}
                  saving={updateProject.isPending}
                  disabled={!dirty || !name.trim()}
                  saved={saved && !dirty}
                  onClick={saveDetails}
                />
              </PermissionGate>
            }
          >
            <div className="space-y-2">
              <Label htmlFor="project-name">{t('projectSettings.name')}</Label>
              <Input
                id="project-name"
                value={name}
                onChange={(e) => { setName(e.target.value); setSaved(false); }}
                disabled={!canEditProject || updateProject.isPending}
                className="max-w-sm"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="project-description">
                {t('projectSettings.description')}{' '}
                <span className="text-xs font-normal text-muted-foreground">{t('common.optional')}</span>
              </Label>
              <Textarea
                id="project-description"
                value={description}
                onChange={(e) => { setDescription(e.target.value); setSaved(false); }}
                disabled={!canEditProject || updateProject.isPending}
                rows={2}
                className="max-w-lg"
              />
            </div>
            <div className="space-y-1">
              <p className="mono-label">{t('projectSettings.id')}</p>
              <code className="break-all font-mono text-xs text-muted-foreground">{project.id}</code>
            </div>
          </FormSection>

          <FormSection
            title={t('projectSettings.idempotency.title')}
            description={t('projectSettings.idempotency.description')}
          >
            <div className="rounded-xl border border-rail bg-card shadow-card">
              <PolicyRow
                icon={Fingerprint}
                title={t('projectSettings.idempotency.rowTitle')}
                hint={t(`projectSettings.idempotency.explain.${idempotency}`)}
              >
                <SegmentedChoice<IdempotencyChoice>
                  ariaLabel={t('projectSettings.idempotency.title')}
                  value={idempotency}
                  disabled={!canEditProject || updateProject.isPending}
                  onChange={setIdempotency}
                  options={IDEMPOTENCY_CHOICES.map((choice) => ({
                    value: choice,
                    label: t(`projectSettings.idempotency.choice.${choice}`),
                  }))}
                />
              </PolicyRow>
            </div>
          </FormSection>
        </div>
      </div>
    </div>
  );
}
