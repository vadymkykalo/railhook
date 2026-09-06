import { useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Check, Copy, Eye, EyeOff, KeyRound, Loader2, Plus, RefreshCw, Send, X,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { endpointsApi, type EndpointTestResponse } from '../api/endpoints.api';
import { subscriptionsApi } from '../api/subscriptions.api';
import { useEventTypes, useProject, queryKeys } from '../api/queries';
import { useQueryClient } from '@tanstack/react-query';
import AttemptRail, { type RailAttempt } from '../components/AttemptRail';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { cn } from '../lib/utils';
import { usePermissions } from '../auth/usePermissions';
import { useCopyToClipboard } from '../hooks/useCopyToClipboard';
import SignatureSchemePicker, { sendsStandardHeaders } from '../components/SignatureSchemePicker';
import type { SignatureScheme } from '../types/api.types';

/**
 * Creating a connection.
 *
 * This used to be a 693-line page you navigated to — a fourth destination for
 * the job the Connections tab already names. It is now a flow: `ConnectionsPage`
 * opens it in a dialog, and the route that still points here renders the same
 * flow full-page so an existing link keeps working.
 *
 * The step order also changed. The old wizard collected the retry ladder in
 * step 5 but had already written the subscriptions in step 4, so a ladder the
 * person chose was silently dropped. Here the subscriptions are written once,
 * at the end, with the ladder that was actually chosen.
 */

function generateSecret(): string {
  const array = new Uint8Array(32);
  crypto.getRandomValues(array);
  return Array.from(array, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** Seconds → the shortest honest unit, for a ladder preview. */
export function formatLadderDelay(seconds: number): string {
  if (seconds >= 86400) return `${Math.round(seconds / 86400)}d`;
  if (seconds >= 3600) return `${Math.round(seconds / 3600)}h`;
  if (seconds >= 60) return `${Math.round(seconds / 60)}m`;
  return `${seconds}s`;
}

/** A comma-separated delay list → rail ticks, ignoring anything unparseable. */
export function ladderTicks(retryDelays: string, maxAttempts: number): RailAttempt[] {
  const delays = retryDelays
    .split(',')
    .map((d) => Number(d.trim()))
    .filter((n) => Number.isFinite(n) && n >= 0);

  const ticks: RailAttempt[] = [{ number: 1, outcome: 'scheduled', delayMinutes: 0 }];
  let cumulative = 0;
  for (let i = 0; i < Math.max(0, maxAttempts - 1); i++) {
    cumulative += delays[Math.min(i, delays.length - 1)] ?? 0;
    ticks.push({ number: i + 2, outcome: 'scheduled', delayMinutes: cumulative / 60 });
  }
  return ticks;
}

/**
 * A secret, never legible until asked for.
 *
 * A signing secret is the one field on this screen that must not survive a
 * screenshot, a shared screen or a scrolled-past terminal, so it renders as
 * dots until the reader asks for it and goes back to dots when the dialog
 * closes. Copy does not require revealing.
 */
export function SecretField({ secret, label }: { secret: string; label?: string }) {
  const { t } = useTranslation();
  const [revealed, setRevealed] = useState(false);
  const { copied, copy: copyToClipboard } = useCopyToClipboard();

  const copy = async () => {
    if (await copyToClipboard(secret)) {
      showSuccess(t('endpoints.toast.secretCopied'));
    } else {
      showApiError(new Error('clipboard'), 'connectionSetup.secret.copyFailed');
    }
  };

  return (
    <div className="space-y-1.5">
      {label && <div className="mono-label">{label}</div>}
      <div className="flex w-full items-start gap-2">
        {/* w-0 + break-all: a 64-character secret must wrap inside the row,
            never widen the dialog it sits in. */}
        <code
          className="w-0 min-w-0 flex-1 break-all rounded-md border border-rail bg-secondary/50 px-3 py-2 font-mono text-xs leading-6"
          data-testid="signing-secret"
        >
          {revealed ? secret : '•'.repeat(Math.min(secret.length, 48))}
        </code>
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="flex-shrink-0"
          onClick={() => setRevealed((v) => !v)}
          aria-label={revealed ? t('connectionSetup.secret.hide', 'Hide secret') : t('connectionSetup.secret.reveal', 'Reveal secret')}
          title={revealed ? t('connectionSetup.secret.hide', 'Hide secret') : t('connectionSetup.secret.reveal', 'Reveal secret')}
        >
          {revealed ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="flex-shrink-0"
          onClick={copy}
          aria-label={t('endpoints.secretDialog.copy')}
          title={t('endpoints.secretDialog.copy')}
        >
          {copied ? <Check className="h-4 w-4 text-ok" /> : <Copy className="h-4 w-4" />}
        </Button>
      </div>
    </div>
  );
}

type Step = 'url' | 'secret' | 'test' | 'events' | 'retry';

const STEPS: Step[] = ['url', 'secret', 'test', 'events', 'retry'];

const SUGGESTED_EVENT_TYPES = [
  'order.created',
  'order.updated',
  'payment.succeeded',
  'payment.failed',
  'customer.created',
];

export interface ConnectionSetupFlowProps {
  projectId: string;
  /** Called once the connection exists, so the opener can close and refresh. */
  onDone?: () => void;
  /** Rendered as the flow's own cancel control when the opener wants one. */
  onCancel?: () => void;
}

/**
 * The five steps, laid out for whatever frame holds them: a dialog on the
 * Connections tab, or the full page below.
 */
export function ConnectionSetupFlow({ projectId, onDone, onCancel }: ConnectionSetupFlowProps) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { canManageEndpoints } = usePermissions();
  const { data: catalog = [] } = useEventTypes(projectId);

  const [stepIndex, setStepIndex] = useState(0);
  const step = STEPS[stepIndex];

  const [url, setUrl] = useState('');
  const [description, setDescription] = useState('');
  const [creatingEndpoint, setCreatingEndpoint] = useState(false);
  const [endpointId, setEndpointId] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [standardSecret, setStandardSecret] = useState<string | null>(null);
  const [signatureScheme, setSignatureScheme] = useState<SignatureScheme>('BOTH');
  const [savingScheme, setSavingScheme] = useState(false);

  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<EndpointTestResponse | null>(null);

  const [eventTypes, setEventTypes] = useState<string[]>(['']);

  const [maxAttempts, setMaxAttempts] = useState(7);
  const [timeoutSeconds, setTimeoutSeconds] = useState(30);
  const [retryDelays, setRetryDelays] = useState('60,300,900,3600,21600,86400');
  const [finishing, setFinishing] = useState(false);

  const suggestions = useMemo(() => {
    const fromCatalog = catalog.map((entry) => entry.name).filter(Boolean);
    return (fromCatalog.length > 0 ? fromCatalog : SUGGESTED_EVENT_TYPES).slice(0, 6);
  }, [catalog]);

  const chosenTypes = eventTypes.map((type) => type.trim()).filter(Boolean);
  const ticks = useMemo(() => ladderTicks(retryDelays, maxAttempts), [retryDelays, maxAttempts]);

  const goNext = () => setStepIndex((i) => Math.min(i + 1, STEPS.length - 1));
  const goBack = () => setStepIndex((i) => Math.max(i - 1, 0));

  const handleCreateEndpoint = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!url.trim()) return;
    setCreatingEndpoint(true);
    try {
      const generated = generateSecret();
      const endpoint = await endpointsApi.create(projectId, {
        url: url.trim(),
        description: description.trim() || undefined,
        enabled: true,
        secret: generated,
      });
      setEndpointId(endpoint.id);
      setSecret(endpoint.secret ?? generated);
      setStandardSecret(endpoint.standardWebhooksSecret ?? null);
      setSignatureScheme(endpoint.signatureScheme ?? 'BOTH');
      qc.invalidateQueries({ queryKey: queryKeys.endpoints.list(projectId) });
      showSuccess(t('connectionSetup.toast.endpointCreated'));
      goNext();
    } catch (err) {
      showApiError(err, 'endpoints.toast.createFailed');
    } finally {
      setCreatingEndpoint(false);
    }
  };

  /**
   * The endpoint already exists by this step, so a change is a write, not a
   * pending form value. `rateLimitPerSecond` is absent here only because the
   * wizard has not offered it yet — the API reads that field unconditionally,
   * so an update that omits one it *has* been given would clear it.
   */
  const handleSchemeChange = async (next: SignatureScheme) => {
    if (!endpointId) return;
    const previous = signatureScheme;
    setSignatureScheme(next);
    setSavingScheme(true);
    try {
      await endpointsApi.update(projectId, endpointId, {
        url: url.trim(),
        description: description.trim() || undefined,
        enabled: true,
        signatureScheme: next,
      });
      qc.invalidateQueries({ queryKey: queryKeys.endpoints.list(projectId) });
    } catch (err) {
      // A scheme the endpoint does not have must not go on looking chosen.
      setSignatureScheme(previous);
      showApiError(err, 'signatureScheme.saveFailed');
    } finally {
      setSavingScheme(false);
    }
  };

  const handleTest = async () => {
    if (!endpointId) return;
    setTesting(true);
    setTestResult(null);
    try {
      const result = await endpointsApi.test(projectId, endpointId);
      setTestResult(result);
      if (result.success) showSuccess(t('connectionSetup.toast.testPassed'));
    } catch (err) {
      showApiError(err, 'endpoints.toast.testError');
    } finally {
      setTesting(false);
    }
  };

  const handleFinish = async () => {
    if (!endpointId || chosenTypes.length === 0) return;
    setFinishing(true);
    try {
      for (const eventType of chosenTypes) {
        await subscriptionsApi.create(projectId, {
          endpointId,
          eventType,
          enabled: true,
          maxAttempts,
          timeoutSeconds,
          retryDelays,
        });
      }
      qc.invalidateQueries({ queryKey: queryKeys.subscriptions.list(projectId) });
      qc.invalidateQueries({ queryKey: queryKeys.endpoints.list(projectId) });
      showSuccess(t('connectionSetup.toast.connectionCreated', 'Connection created'));
      onDone?.();
    } catch (err) {
      showApiError(err, 'toast.errors.server');
    } finally {
      setFinishing(false);
    }
  };

  const stepTitle: Record<Step, string> = {
    url: t('connectionSetup.steps.url.title'),
    secret: t('connectionSetup.steps.secret.title'),
    test: t('connectionSetup.steps.test.title'),
    events: t('connectionSetup.steps.events.title'),
    retry: t('connectionSetup.steps.retry.title'),
  };

  return (
    <div className="space-y-5">
      {/* Where in the flow we are — ticks, not a progress bar: the steps are
          discrete and one of them (the test) is optional. */}
      <div>
        <div className="mono-label mb-2">
          {t('connectionSetup.stepCounter', 'Step {{n}} of {{total}} · {{name}}', {
            n: stepIndex + 1,
            total: STEPS.length,
            name: stepTitle[step],
          })}
        </div>
        <ol className="flex items-center gap-1.5" aria-hidden>
          {STEPS.map((s, i) => (
            <li
              key={s}
              className={cn(
                'h-1 flex-1 rounded-full transition-colors',
                i < stepIndex ? 'bg-primary' : i === stepIndex ? 'bg-primary/60' : 'bg-rail'
              )}
            />
          ))}
        </ol>
      </div>

      {step === 'url' && (
        <form id="connection-step-url" onSubmit={handleCreateEndpoint} className="space-y-4">
          <p className="text-sm text-muted-foreground">{t('connectionSetup.steps.url.desc')}</p>
          <div className="space-y-2">
            <Label htmlFor="connection-url">{t('connectionSetup.steps.url.urlLabel')}</Label>
            <Input
              id="connection-url"
              type="url"
              className="font-mono text-sm"
              placeholder="https://api.example.com/webhooks"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              required
              disabled={creatingEndpoint || !canManageEndpoints}
              autoFocus
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="connection-description">{t('connectionSetup.steps.url.descLabel')}</Label>
            <Textarea
              id="connection-description"
              placeholder={t('connectionSetup.steps.url.descPlaceholder')}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              disabled={creatingEndpoint || !canManageEndpoints}
              rows={2}
            />
          </div>
        </form>
      )}

      {step === 'secret' && (
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">{t('connectionSetup.steps.secret.desc')}</p>
          {secret ? (
            <>
              <SecretField secret={secret} label={t('connectionSetup.secret.label', 'Signing secret')} />
              {/* Derived from the same secret, but the only form a Standard
                  Webhooks library will take — and useless to an endpoint that
                  is sent no Standard Webhooks headers. */}
              {standardSecret && sendsStandardHeaders(signatureScheme) && (
                <>
                  <SecretField secret={standardSecret} label={t('connectionSetup.secret.standardLabel')} />
                  <p className="text-xs text-muted-foreground">{t('connectionSetup.secret.standardHint')}</p>
                </>
              )}
              <div className="flex items-start gap-2.5 rounded-lg border border-retry/30 bg-retry-soft p-3">
                <KeyRound className="mt-0.5 h-4 w-4 flex-shrink-0 text-retry" aria-hidden />
                <p className="text-xs text-retry">{t('connectionSetup.steps.secret.warning')}</p>
              </div>
              <SignatureSchemePicker
                value={signatureScheme}
                onChange={handleSchemeChange}
                disabled={savingScheme || !canManageEndpoints}
              />
            </>
          ) : (
            <p className="text-sm text-muted-foreground">{t('connectionSetup.steps.secret.pending')}</p>
          )}
        </div>
      )}

      {step === 'test' && (
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">{t('connectionSetup.steps.test.desc')}</p>
          <Button type="button" onClick={handleTest} disabled={testing || !endpointId} variant="outline">
            {testing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
            {t('connectionSetup.steps.test.send')}
          </Button>
          {testResult && (
            <div className="rounded-lg border border-rail p-3">
              <StatusBadge
                kind={testResult.success ? 'ok' : 'halt'}
                label={testResult.success ? t('connectionSetup.steps.test.passed') : t('connectionSetup.steps.test.failed')}
              />
              <p className="mt-2 font-mono text-xs text-muted-foreground">
                HTTP {testResult.httpStatusCode ?? '—'} · {testResult.latencyMs}ms
              </p>
              {testResult.errorMessage && (
                <p className="mt-1 text-xs text-halt">{testResult.errorMessage}</p>
              )}
              {!testResult.success && (
                <p className="mt-2 text-xs text-muted-foreground">{t('connectionSetup.steps.test.retryHint')}</p>
              )}
            </div>
          )}
        </div>
      )}

      {step === 'events' && (
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">{t('connectionSetup.steps.events.desc')}</p>
          <div className="flex flex-wrap gap-1.5">
            {suggestions.map((type) => (
              <button
                key={type}
                type="button"
                onClick={() =>
                  setEventTypes((prev) =>
                    prev.includes(type) ? prev : [...prev.filter((v) => v.trim()), type]
                  )
                }
                className="rounded-md border border-rail px-2 py-0.5 font-mono text-[11px] text-muted-foreground transition-colors hover:border-primary hover:text-foreground"
              >
                + {type}
              </button>
            ))}
          </div>
          <div className="space-y-2">
            {eventTypes.map((type, i) => (
              <div key={i} className="flex items-center gap-2">
                <Input
                  value={type}
                  onChange={(e) =>
                    setEventTypes((prev) => prev.map((v, index) => (index === i ? e.target.value : v)))
                  }
                  placeholder="order.created"
                  className="font-mono text-sm"
                  aria-label={t('connectionSetup.steps.events.rowLabel', 'Event type {{n}}', { n: i + 1 })}
                />
                {eventTypes.length > 1 && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => setEventTypes((prev) => prev.filter((_, index) => index !== i))}
                    aria-label={t('connectionSetup.steps.events.remove', 'Remove event type')}
                    title={t('connectionSetup.steps.events.remove', 'Remove event type')}
                  >
                    <X className="h-3.5 w-3.5" />
                  </Button>
                )}
              </div>
            ))}
          </div>
          <Button type="button" variant="ghost" size="sm" onClick={() => setEventTypes((prev) => [...prev, ''])}>
            <Plus className="h-3.5 w-3.5" /> {t('connectionSetup.steps.events.add')}
          </Button>
        </div>
      )}

      {step === 'retry' && (
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">{t('connectionSetup.steps.retry.desc')}</p>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="connection-attempts">{t('connectionSetup.steps.retry.maxAttempts')}</Label>
              <Input
                id="connection-attempts"
                type="number"
                min={1}
                max={20}
                value={maxAttempts}
                onChange={(e) => setMaxAttempts(Number(e.target.value))}
                className="font-mono text-sm"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="connection-timeout">{t('connectionSetup.steps.retry.timeout')}</Label>
              <Input
                id="connection-timeout"
                type="number"
                min={1}
                max={60}
                value={timeoutSeconds}
                onChange={(e) => setTimeoutSeconds(Number(e.target.value))}
                className="font-mono text-sm"
              />
            </div>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="connection-delays">{t('connectionSetup.steps.retry.delays')}</Label>
            <Input
              id="connection-delays"
              value={retryDelays}
              onChange={(e) => setRetryDelays(e.target.value)}
              placeholder="60,300,900,3600,21600,86400"
              className="font-mono text-sm"
            />
            <p className="text-xs text-muted-foreground">{t('connectionSetup.steps.retry.delaysHint')}</p>
          </div>
          <div className="rounded-lg border border-rail p-4">
            <div className="mono-label mb-2">{t('connectionSetup.steps.retry.preview')}</div>
            <AttemptRail
              attempts={ticks}
              size="full"
              ariaLabel={t('connectionSetup.steps.retry.railLabel', 'Retry ladder: {{count}} attempts over {{span}}', {
                count: ticks.length,
                span: formatLadderDelay(Math.round((ticks[ticks.length - 1]?.delayMinutes ?? 0) * 60)),
              })}
            />
          </div>
          {chosenTypes.length > 0 && (
            <p className="text-xs text-muted-foreground">
              {t('connectionSetup.steps.retry.appliesTo', 'Applies to every subscription this connection creates.')}
            </p>
          )}
        </div>
      )}

      <div className="flex items-center justify-between gap-2 border-t border-rail pt-4">
        <div>
          {stepIndex > 0 && (
            <Button type="button" variant="ghost" onClick={goBack} disabled={finishing}>
              {t('connectionSetup.back', 'Back')}
            </Button>
          )}
        </div>
        <div className="flex items-center gap-2">
          {onCancel && (
            <Button type="button" variant="outline" onClick={onCancel} disabled={finishing}>
              {t('common.cancel')}
            </Button>
          )}
          {step === 'url' && (
            <Button type="submit" form="connection-step-url" disabled={creatingEndpoint || !canManageEndpoints}>
              {creatingEndpoint && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('connectionSetup.steps.url.create')}
            </Button>
          )}
          {(step === 'secret' || step === 'test') && (
            <Button type="button" onClick={goNext}>
              {t('connectionSetup.next')}
            </Button>
          )}
          {step === 'events' && (
            <Button type="button" onClick={goNext} disabled={chosenTypes.length === 0}>
              {t('connectionSetup.next')}
            </Button>
          )}
          {step === 'retry' && (
            <Button type="button" onClick={handleFinish} disabled={finishing || chosenTypes.length === 0}>
              {finishing ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
              {t('connectionSetup.finish', 'Create connection')}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * The route that used to be the wizard's home. Another workstream owns the
 * router, so the path stays live and renders the same flow full-page.
 */
export default function ConnectionSetupPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const {
    data: project, isLoading, isError, error, refetch, isRefetching,
  } = useProject(projectId);

  const backToConnections = () => navigate(`/admin/projects/${projectId}/connections`);

  if (isLoading) {
    return (
      <PageSkeleton maxWidth="max-w-2xl">
        <SkeletonCards count={2} height="h-40" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  // A wizard whose fetch failed would otherwise write into a project we could
  // not confirm exists — an empty form, then a 404 on submit.
  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={project?.name}
        title={t('connectionSetup.title')}
        description={t('connectionSetup.pageDesc', 'One endpoint, its signing secret and the event types it is subscribed to.')}
      />
      <div className="max-w-2xl">
        {projectId && (
          <ConnectionSetupFlow
            projectId={projectId}
            onDone={backToConnections}
            onCancel={backToConnections}
          />
        )}
      </div>
    </div>
  );
}
