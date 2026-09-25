import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowRight, Check, Sparkles, X } from 'lucide-react';
import { useOnboardingStatus, useIncomingSources } from '../api/queries';
import {
  isDismissed, progressOf, readIntent, setDismissed, stepsFor, trackFor,
  writeIntent, type Step, type StepKey, type Track,
} from '../lib/onboarding';
import { Card } from './ui/card';
import { Button } from './ui/button';
import { cn } from '../lib/utils';
import { docsUrl } from '../lib/docsUrl';
import IntentPicker from './IntentPicker';
import ConnectionSetupDialog from './ConnectionSetupDialog';
import SendTestEventModal from './SendTestEventModal';

/** Every row derives from lib/onboarding.ts, so none can claim more than its evidence. */

type Launch = { kind: 'dialog'; dialog: 'connection' | 'testEvent' } | { kind: 'route'; segment: string };

const LAUNCH: Record<StepKey, Launch> = {
  createConnection: { kind: 'dialog', dialog: 'connection' },
  // The plaintext key is shown once, on the page that owns that ritual.
  createApiKey: { kind: 'route', segment: 'api-keys' },
  sendEvent: { kind: 'dialog', dialog: 'testEvent' },
  seeDelivery: { kind: 'route', segment: 'deliveries' },
  createSource: { kind: 'route', segment: 'incoming-sources' },
  verifySource: { kind: 'route', segment: 'incoming-sources' },
  addDestination: { kind: 'route', segment: 'incoming-sources' },
};

function StepRow({ step, index, onLaunch }: { step: Step; index: number; onLaunch: () => void }) {
  const { t } = useTranslation();

  return (
    <li data-done={step.done} className="border-t border-rail first:border-t-0">
      <button
        type="button"
        onClick={onLaunch}
        className={cn(
          'flex w-full items-start gap-3 px-1 py-3 text-left transition-colors',
          step.done ? 'hover:bg-secondary/40' : 'hover:bg-secondary'
        )}
      >
        <span
          aria-hidden
          className={cn(
            'mt-0.5 flex h-5 w-5 flex-shrink-0 items-center justify-center border font-mono text-[10px]',
            step.done
              ? 'border-transparent bg-ok-soft text-ok'
              : 'border-rail text-muted-foreground'
          )}
        >
          {step.done ? <Check className="h-3 w-3" /> : index + 1}
        </span>
        <span className="min-w-0 flex-1">
          <span className={cn('block text-sm font-medium', step.done && 'text-muted-foreground line-through')}>
            {t(`onboarding.steps.${step.key}`)}
          </span>
          {!step.done && (
            <span className="mt-0.5 block text-[13px] leading-snug text-muted-foreground">
              {t(`onboarding.steps.${step.key}Desc`)}
            </span>
          )}
        </span>
        {!step.done && <ArrowRight className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" aria-hidden />}
      </button>
    </li>
  );
}

export default function GettingStarted({ projectId }: { projectId: string | undefined }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();

  const { data: status, isLoading } = useOnboardingStatus(projectId);
  // The one question the onboarding endpoint can't answer: does any source verify.
  const { data: sourcePage } = useIncomingSources(
    status?.hasIncomingSources ? projectId : undefined,
    0
  );

  const [intent, setIntent] = useState<Track | null>(() => readIntent());
  const [dismissed, setLocalDismissed] = useState(() => (projectId ? isDismissed(projectId) : false));
  const [dialog, setDialog] = useState<'connection' | 'testEvent' | null>(null);

  const track = status ? trackFor(status, intent) : null;

  const steps = useMemo(
    () => (status && track ? stepsFor(track, { status, sources: sourcePage?.content ?? [] }) : []),
    [status, track, sourcePage]
  );
  const progress = progressOf(steps);

  if (!projectId) return null;

  const handleDismiss = () => {
    setDismissed(projectId, true);
    setLocalDismissed(true);
  };

  const handleRestore = () => {
    setDismissed(projectId, false);
    setLocalDismissed(false);
  };

  if (dismissed) {
    return (
      <div className="mb-4 flex justify-end">
        <Button variant="ghost" size="sm" className="text-muted-foreground" onClick={handleRestore}>
          <Sparkles className="h-3.5 w-3.5" />
          {t('onboarding.restore')}
        </Button>
      </div>
    );
  }

  // Render nothing while loading, or a finished checklist un-ticks and re-ticks on every load.
  if (isLoading || !status) return null;

  const handleLaunch = (key: StepKey) => {
    const launch = LAUNCH[key];
    if (launch.kind === 'dialog') setDialog(launch.dialog);
    else navigate(`/admin/projects/${projectId}/${launch.segment}`);
  };

  const chooseIntent = (chosen: Track) => {
    writeIntent(chosen);
    setIntent(chosen);
  };

  return (
    <>
      <Card className="mb-4 p-5">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h3 className="text-sm font-medium">{t('onboarding.title')}</h3>
            {track && !progress.allDone && (
              <p className="mt-0.5 text-[13px] text-muted-foreground">{t('onboarding.subtitle')}</p>
            )}
          </div>
          <div className="flex flex-shrink-0 items-center gap-2">
            {track && !progress.allDone && (
              <span className="mono-label tabular-nums">
                {t('onboarding.progress', { done: progress.done, total: progress.total })}
              </span>
            )}
            <Button
              variant="ghost"
              size="icon-sm"
              className="h-7 w-7 text-muted-foreground"
              onClick={handleDismiss}
              title={t('onboarding.dismiss')}
              aria-label={t('onboarding.dismiss')}
            >
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>

        {!track ? (
          <div className="mt-4 max-w-md">
            <p className="mb-3 text-sm font-medium">{t('auth.intent.title')}</p>
            <IntentPicker onSelect={chooseIntent} />
          </div>
        ) : progress.allDone ? (
          <div className="mt-4 flex flex-wrap items-center gap-3">
            <span className="flex h-8 w-8 items-center justify-center bg-ok-soft">
              <Check className="h-4 w-4 text-ok" aria-hidden />
            </span>
            <p className="text-sm font-medium">{t('onboarding.allDone')}</p>
            <Button variant="outline" size="sm" onClick={() => window.location.assign(docsUrl(i18n.language))}>
              {t('onboarding.allDoneAction')}
              <ArrowRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        ) : (
          <ol className="mt-3">
            {steps.map((step, i) => (
              <StepRow key={step.key} step={step} index={i} onLaunch={() => handleLaunch(step.key)} />
            ))}
          </ol>
        )}
      </Card>

      <ConnectionSetupDialog
        projectId={projectId}
        open={dialog === 'connection'}
        onOpenChange={(open) => setDialog(open ? 'connection' : null)}
      />

      <SendTestEventModal
        projectId={projectId}
        open={dialog === 'testEvent'}
        onClose={() => setDialog(null)}
      />
    </>
  );
}
