import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { usePermissions } from '../auth/usePermissions';
import CreateProjectDialog from './CreateProjectDialog';
import VerificationGate from './VerificationGate';
import { Card } from './ui/card';
import { Button } from './ui/button';

const NEXT_STEPS = ['createApiKey', 'createConnection', 'sendEvent'] as const;

export default function FirstProjectCard() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { canCreateProject } = usePermissions();
  const [creating, setCreating] = useState(false);

  return (
    <>
      <Card className="p-5 sm:p-6">
        <h3 className="text-sm font-medium">{t('onboarding.title')}</h3>
        <p className="mt-0.5 text-[13px] text-muted-foreground">{t('setup.firstRunSubtitle')}</p>

        <ol className="mt-4">
          <li className="flex flex-wrap items-start gap-3 border-b border-rail pb-4">
            <span aria-hidden className="mt-0.5 flex h-5 w-5 flex-shrink-0 items-center justify-center bg-primary font-mono text-[10px] text-primary-foreground">1</span>
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-medium">{t('setup.firstStep')}</span>
              <span className="mt-0.5 block text-[13px] leading-snug text-muted-foreground">{t('setup.firstStepDesc')}</span>
            </span>
            {canCreateProject ? (
              <VerificationGate>
                <Button size="sm" className="max-sm:w-full" onClick={() => setCreating(true)}>
                  {t('setup.createProject')}
                  <ArrowRight className="h-3.5 w-3.5" aria-hidden />
                </Button>
              </VerificationGate>
            ) : (
              <span className="text-[13px] text-muted-foreground">{t('setup.needsProjectViewer')}</span>
            )}
          </li>
          {NEXT_STEPS.map((key, i) => (
            <li key={key} className="flex items-start gap-3 border-b border-rail py-3 last:border-b-0 last:pb-0">
              <span aria-hidden className="mt-0.5 flex h-5 w-5 flex-shrink-0 items-center justify-center border border-rail font-mono text-[10px] text-muted-foreground">{i + 2}</span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm text-muted-foreground">{t(`onboarding.steps.${key}`)}</span>
              </span>
            </li>
          ))}
        </ol>
      </Card>

      <CreateProjectDialog
        open={creating}
        onOpenChange={setCreating}
        onCreated={() => navigate('/admin/dashboard', { replace: true })}
      />
    </>
  );
}
