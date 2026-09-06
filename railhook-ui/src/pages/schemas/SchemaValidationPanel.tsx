import { useTranslation } from 'react-i18next';
import { Fingerprint, ShieldCheck } from 'lucide-react';
import { useProject, useUpdateProject } from '../../api/queries';
import { showApiError, showSuccess } from '../../lib/toast';
import { ErrorState } from '../../components/EmptyState';
import { SkeletonRows } from '../../components/PageSkeleton';
import { cn } from '../../lib/utils';

/**
 * What happens to an event that does not match its schema.
 *
 * Two settings, one row each: whether an event is validated at all and what a
 * failed validation costs, and whether a duplicate is rejected. They used to be
 * two cards tinted green, blue and purple — colours the palette reserves for
 * statuses — so the choice is now carried by which segment is selected.
 */

function SegmentedChoice<T extends string>({
  value, options, onChange, disabled, ariaLabel,
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (value: T) => void;
  disabled?: boolean;
  ariaLabel: string;
}) {
  return (
    <div role="group" aria-label={ariaLabel} className="flex flex-shrink-0 gap-0.5 rounded-lg border border-rail p-0.5">
      {options.map((option) => {
        const active = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={active}
            disabled={disabled}
            onClick={() => onChange(option.value)}
            className={cn(
              'rounded-md px-3 py-1 text-xs transition-colors disabled:opacity-50',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              active ? 'bg-primary font-medium text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

function PolicyRow({
  icon: Icon, title, hint, children,
}: {
  icon: typeof ShieldCheck;
  title: string;
  hint: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex min-w-0 items-start gap-3">
        <Icon className="mt-0.5 h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />
        <div className="min-w-0">
          <p className="text-[13px] font-medium">{title}</p>
          <p className="text-xs text-muted-foreground">{hint}</p>
        </div>
      </div>
      {children}
    </div>
  );
}

type ValidationChoice = 'OFF' | 'WARN' | 'BLOCK';

export default function SchemaValidationPanel({ projectId }: { projectId: string }) {
  const { t } = useTranslation();
  const {
    data: project, isLoading, isError, error, refetch, isRefetching,
  } = useProject(projectId);
  const updateMutation = useUpdateProject(projectId);

  if (isLoading) return <SkeletonRows count={2} height="h-[72px]" />;

  // Both rows are a switch reading its position from the project. Rendering
  // nothing on a failed fetch hid the two settings that decide whether a
  // malformed event is rejected.
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

  const setIdempotency = async (policy: string) => {
    try {
      await updateMutation.mutateAsync({
        name: project.name,
        description: project.description,
        idempotencyPolicy: policy,
      });
      showSuccess(t('schemas.idempotency.saved'));
    } catch (err: any) {
      showApiError(err, 'schemas.idempotency.saveFailed');
    }
  };

  return (
    <div className="divide-y divide-rail rounded-xl border border-rail bg-card shadow-card">
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

      <PolicyRow
        icon={Fingerprint}
        title={t('schemas.idempotency.title')}
        hint={t('schemas.idempotency.hint')}
      >
        <SegmentedChoice
          ariaLabel={t('schemas.idempotency.title')}
          value={project.idempotencyPolicy || 'NONE'}
          disabled={updateMutation.isPending}
          onChange={setIdempotency}
          options={[
            { value: 'NONE', label: t('schemas.idempotency.none') },
            { value: 'AUTO', label: t('schemas.idempotency.auto') },
            { value: 'REQUIRED', label: t('schemas.idempotency.required') },
          ]}
        />
      </PolicyRow>
    </div>
  );
}
