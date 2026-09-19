import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ShieldCheck } from 'lucide-react';
import { useProject, useUpdateProject } from '../../api/queries';
import { showApiError, showSuccess } from '../../lib/toast';
import { ErrorState } from '../../components/EmptyState';
import { SkeletonRows } from '../../components/PageSkeleton';
import SegmentedChoice, { PolicyRow } from '../../components/SegmentedChoice';

/**
 * What happens to an event that does not match its schema.
 *
 * The idempotency policy used to be the second row here. It decides whether a repeated event is
 * dropped, which has nothing to do with the event's shape, so it moved to the project's settings
 * and this panel only says where it went.
 */

type ValidationChoice = 'OFF' | 'WARN' | 'BLOCK';

export default function SchemaValidationPanel({ projectId }: { projectId: string }) {
  const { t } = useTranslation();
  const {
    data: project, isLoading, isError, error, refetch, isRefetching,
  } = useProject(projectId);
  const updateMutation = useUpdateProject(projectId);

  if (isLoading) return <SkeletonRows count={1} height="h-[72px]" />;

  // The row is a switch reading its position from the project. Rendering nothing on a failed
  // fetch hid the setting that decides whether a malformed event is rejected.
  if (isError || !project) {
    return <ErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />;
  }

  const validation: ValidationChoice = project.schemaValidationEnabled
    ? (project.schemaValidationPolicy === 'BLOCK' ? 'BLOCK' : 'WARN')
    : 'OFF';

  const setValidation = async (choice: ValidationChoice) => {
    try {
      await updateMutation.mutateAsync({
        name: project.name,
        description: project.description,
        schemaValidationEnabled: choice !== 'OFF',
        schemaValidationPolicy: choice === 'OFF' ? project.schemaValidationPolicy : choice,
      });
      showSuccess(t('schemas.validation.saved'));
    } catch (err: any) {
      showApiError(err, 'schemas.validation.saveFailed');
    }
  };

  return (
    <div className="rounded-xl border border-rail bg-card shadow-card">
      <PolicyRow
        icon={ShieldCheck}
        title={t('schemas.validation.title')}
        hint={t('schemas.validation.enabledHint')}
      >
        <SegmentedChoice<ValidationChoice>
          ariaLabel={t('schemas.validation.title')}
          value={validation}
          disabled={updateMutation.isPending}
          onChange={setValidation}
          options={[
            { value: 'OFF', label: t('schemas.validation.off') },
            { value: 'WARN', label: t('schemas.validation.warn') },
            { value: 'BLOCK', label: t('schemas.validation.block') },
          ]}
        />
      </PolicyRow>
      <p className="border-t border-rail px-4 py-2.5 text-xs text-muted-foreground">
        {t('schemas.idempotencyMoved')}{' '}
        <Link to={`/admin/projects/${projectId}/project-settings`} className="text-primary underline-offset-4 hover:underline">
          {t('schemas.idempotencyMovedLink')}
        </Link>
      </p>
    </div>
  );
}
