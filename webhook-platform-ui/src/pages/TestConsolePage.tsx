import { useState, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import {
  Send, Play, Loader2, ChevronDown, ChevronRight, Zap, Timer,
  AlertTriangle, FileJson2, Copy, Check, ArrowRight, ShieldCheck,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showWarning } from '../lib/toast';
import { eventsApi, type EventResponse } from '../api/events.api';
import { endpointsApi, type EndpointTestResponse } from '../api/endpoints.api';
import { deliveriesApi } from '../api/deliveries.api';
import { useProject, useEndpoints, useSubscriptions, useEventTypes } from '../api/queries';
import type { DeliveryResponse, DeliveryAttemptResponse } from '../types/api.types';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge, { kindOfDeliveryStatus, type StatusKind } from '../components/StatusBadge';
import AttemptRail, { type RailAttempt } from '../components/AttemptRail';
import JsonEditor from '../components/JsonEditor';
import {
  Workbench, WorkbenchPanel, RunControl, ResultFrame, ResultMetric,
  ResultPlaceholder, OutputBlock, ModeSwitch,
} from '../components/Workbench';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Badge } from '../components/ui/badge';
import { Select } from '../components/ui/select';
import { Textarea } from '../components/ui/textarea';
import { cn } from '../lib/utils';
import { formatJson } from '../lib/json';

type ConsoleMode = 'event' | 'ping' | 'verify';

/**
 * Recomputes the signature Railhook would have sent and compares it to the one
 * that arrived. Entirely local — the secret never leaves the browser, which is
 * why this is arithmetic here rather than a call to the API.
 */
async function verifySignature(secret: string, signatureHeader: string, body: string): Promise<boolean> {
  const parts = signatureHeader.split(',');
  const v1Part = parts.find((p) => p.startsWith('v1,') || p.startsWith('v1=')) || parts[0];
  const [timestamp, signature] = (v1Part || '').replace('v1,', '').replace('v1=', '').split('.');
  if (!timestamp || !signature || !secret || !body) return false;

  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const sig = await crypto.subtle.sign('HMAC', key, encoder.encode(`${timestamp}.${body}`));
  const computed = Array.from(new Uint8Array(sig)).map((b) => b.toString(16).padStart(2, '0')).join('');
  return computed === signature;
}

interface DeliveryWithAttempts extends DeliveryResponse {
  attempts?: DeliveryAttemptResponse[];
  endpointUrl?: string;
}

/** A delivery's status is a domain status; the console never invents one. */
function labelKeyOfStatus(status: string): string {
  switch (status) {
    case 'SUCCESS': return 'testConsole.statusSuccess';
    case 'FAILED': return 'testConsole.statusFailed';
    case 'PROCESSING': return 'testConsole.statusProcessing';
    case 'DLQ': return 'testConsole.statusDlq';
    default: return 'testConsole.statusPending';
  }
}

/** Worst-case across every delivery the event created — that is the verdict. */
function rollUp(deliveries: DeliveryWithAttempts[]): StatusKind {
  if (deliveries.length === 0) return 'idle';
  const kinds = deliveries.map((d) => kindOfDeliveryStatus(d.status));
  if (kinds.includes('halt')) return 'halt';
  if (kinds.includes('retry')) return 'retry';
  if (kinds.every((k) => k === 'ok')) return 'ok';
  return 'idle';
}

function railTicks(delivery: DeliveryWithAttempts): RailAttempt[] {
  const start = new Date(delivery.createdAt).getTime();
  return (delivery.attempts ?? []).map((a) => ({
    number: a.attemptNumber,
    outcome: a.httpStatusCode != null && a.httpStatusCode >= 200 && a.httpStatusCode < 300
      ? 'ok'
      : a.errorMessage || a.httpStatusCode != null
        ? 'failed'
        : 'pending',
    delayMinutes: Math.max(0, (new Date(a.createdAt).getTime() - start) / 60000),
    code: a.httpStatusCode,
  }));
}

export default function TestConsolePage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageEndpoints } = usePermissions();
  const {
    data: project, isLoading: projectLoading, isError: projectFailed,
    error: projectError, refetch: refetchProject, isRefetching,
  } = useProject(projectId);
  const {
    data: endpoints = [], isLoading: endpointsLoading, isError: endpointsFailed,
    error: endpointsError, refetch: refetchEndpoints,
  } = useEndpoints(projectId);
  const { data: subscriptions = [] } = useSubscriptions(projectId);
  const { data: catalogTypes = [] } = useEventTypes(projectId);

  const loading = projectLoading || endpointsLoading;

  const [mode, setMode] = useState<ConsoleMode>('event');

  // Send-event input
  const [eventType, setEventType] = useState('');
  const [payload, setPayload] = useState('{\n  "user_id": "123",\n  "action": "created"\n}');
  const [jsonError, setJsonError] = useState('');
  const [sending, setSending] = useState(false);

  // Ping input
  const [selectedEndpointId, setSelectedEndpointId] = useState('');
  const [pinging, setPinging] = useState(false);
  const [pingResult, setPingResult] = useState<EndpointTestResponse | null>(null);

  // Verify-signature input (all local; the secret never leaves the browser)
  const [secret, setSecret] = useState('');
  const [signatureHeader, setSignatureHeader] = useState('');
  const [signedBody, setSignedBody] = useState('');
  const [verifying, setVerifying] = useState(false);
  const [verifyResult, setVerifyResult] = useState<boolean | null>(null);

  // Result
  const [lastEvent, setLastEvent] = useState<EventResponse | null>(null);
  const [deliveries, setDeliveries] = useState<DeliveryWithAttempts[]>([]);
  const [loadingResults, setLoadingResults] = useState(false);
  const [expandedDelivery, setExpandedDelivery] = useState<string | null>(null);
  const [polling, setPolling] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const matchingSubscriptions = subscriptions.filter((sub) => {
    if (!eventType.trim()) return false;
    if (sub.eventType === '**') return true;
    if (sub.eventType === eventType) return true;
    if (sub.eventType.endsWith('.**')) {
      const prefix = sub.eventType.slice(0, -3);
      return eventType.startsWith(prefix + '.') || eventType === prefix;
    }
    if (sub.eventType.endsWith('.*')) {
      const prefix = sub.eventType.slice(0, -2);
      const rest = eventType.slice(prefix.length + 1);
      return eventType.startsWith(prefix + '.') && !rest.includes('.');
    }
    return false;
  });

  const validateJson = (text: string): boolean => {
    try {
      JSON.parse(text);
      setJsonError('');
      return true;
    } catch {
      setJsonError(t('testConsole.invalidJson'));
      return false;
    }
  };

  const handlePayloadChange = (value: string) => {
    setPayload(value);
    if (value.trim()) validateJson(value);
    else setJsonError('');
  };

  const pollDeliveries = useCallback(async (eventId: string, maxPolls = 10) => {
    if (!projectId) return;
    setPolling(true);
    let polls = 0;
    const poll = async () => {
      try {
        const res = await deliveriesApi.listByProject(projectId, { eventId, size: 50 });
        const enriched: DeliveryWithAttempts[] = await Promise.all(
          res.content.map(async (d) => {
            const ep = endpoints.find((e) => e.id === d.endpointId);
            let attempts: DeliveryAttemptResponse[] = [];
            if (d.status !== 'PENDING') {
              try {
                attempts = await deliveriesApi.getAttempts(d.id);
              } catch { /* the attempt list is detail, not the verdict */ }
            }
            return { ...d, endpointUrl: ep?.url, attempts };
          }),
        );
        setDeliveries(enriched);

        const allDone = enriched.length > 0
          && enriched.every((d) => d.status === 'SUCCESS' || d.status === 'FAILED' || d.status === 'DLQ');
        polls++;
        if (!allDone && polls < maxPolls) {
          setTimeout(() => poll(), 2000);
        } else {
          setPolling(false);
        }
      } catch {
        setPolling(false);
      }
    };
    poll();
  }, [projectId, endpoints]);

  const handleSendEvent = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!projectId) return;
    if (!validateJson(payload)) {
      showWarning(t('testConsole.fixJson'));
      return;
    }

    setSending(true);
    setDeliveries([]);
    setPingResult(null);
    setLastEvent(null);
    setExpandedDelivery(null);

    try {
      const data = JSON.parse(payload);
      const response = await eventsApi.sendTestEvent(projectId, { type: eventType, data });
      setLastEvent(response);
      showSuccess(t('testConsole.eventSent', { count: response.deliveriesCreated || 0 }));

      if (response.id) {
        setLoadingResults(true);
        setTimeout(() => {
          pollDeliveries(response.id);
          setLoadingResults(false);
        }, 1000);
      }
    } catch (err: any) {
      showApiError(err, 'toast.errors.server');
    } finally {
      setSending(false);
    }
  };

  const handlePingEndpoint = async () => {
    if (!projectId || !selectedEndpointId) return;
    setPinging(true);
    setPingResult(null);
    setDeliveries([]);
    setLastEvent(null);

    try {
      const result = await endpointsApi.test(projectId, selectedEndpointId);
      setPingResult(result);
    } catch (err: any) {
      showApiError(err, 'toast.errors.server');
    } finally {
      setPinging(false);
    }
  };

  const handleVerify = async (e: React.FormEvent) => {
    e.preventDefault();
    setVerifying(true);
    setVerifyResult(null);
    setLastEvent(null);
    setPingResult(null);
    setDeliveries([]);
    try {
      setVerifyResult(await verifySignature(secret, signatureHeader, signedBody));
    } catch {
      setVerifyResult(false);
    } finally {
      setVerifying(false);
    }
  };

  const copyText = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  if (loading) return <PageSkeleton />;

  // Every mode below sends to an endpoint out of these two fetches. Returning
  // null on a failed one left the console blank with no way back.
  if (projectFailed || endpointsFailed || !project) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState
          error={projectError ?? endpointsError}
          onRetry={() => { refetchProject(); refetchEndpoints(); }}
          retrying={isRefetching}
        />
      </div>
    );
  }

  const getEndpointUrl = (endpointId: string) => endpoints.find((e) => e.id === endpointId)?.url || endpointId;
  const busy = sending || pinging || loadingResults;

  const schemaMatch = catalogTypes.find((et) => et.name === eventType.trim());

  const modeOptions = [
    { value: 'event' as const, label: t('testConsole.sendEvent'), icon: Send },
    { value: 'ping' as const, label: t('testConsole.pingEndpoint'), icon: Zap },
    { value: 'verify' as const, label: t('testConsole.verifySignature'), icon: ShieldCheck },
  ];
  const modeSwitch = (
    <ModeSwitch<ConsoleMode>
      value={mode}
      onChange={setMode}
      ariaLabel={t('testConsole.modeLabel')}
      options={modeOptions}
    />
  );

  const eventInput = (
    <form onSubmit={handleSendEvent} className="space-y-4">
      {modeSwitch}

      <WorkbenchPanel
        eyebrow={t('testConsole.inputEyebrow')}
        title={t('testConsole.sendEvent')}
        description={t('testConsole.sendEventDesc')}
        bodyClassName="space-y-4"
      >
        <div className="space-y-1.5">
          <Label htmlFor="tc-eventType" className="text-xs">{t('testConsole.eventType')}</Label>
          <Input
            id="tc-eventType"
            placeholder="user.created"
            value={eventType}
            onChange={(e) => setEventType(e.target.value)}
            required
            disabled={sending}
            className="font-mono"
          />
          {schemaMatch && (
            <div className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
              <FileJson2 className="h-3 w-3" aria-hidden />
              <span className="font-mono text-foreground">{schemaMatch.name}</span>
              {schemaMatch.latestVersion != null && <span className="font-mono">v{schemaMatch.latestVersion}</span>}
              {schemaMatch.activeVersionStatus === 'ACTIVE' && (
                <StatusBadge kind="ok" label={t('testConsole.schemaActive')} />
              )}
            </div>
          )}
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="tc-payload" className="text-xs">{t('testConsole.payload')}</Label>
          <JsonEditor
            value={payload}
            onChange={handlePayloadChange}
            readOnly={sending}
            minHeight="220px"
            maxHeight="320px"
            placeholder='{"key": "value"}'
          />
          {jsonError && <p className="text-xs text-halt">{jsonError}</p>}
        </div>

        {eventType.trim() && (
          <div className="rounded-lg border border-rail p-3">
            <p className="mono-label mb-2">{t('testConsole.expectedDeliveries')}</p>
            {matchingSubscriptions.length === 0 ? (
              <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <AlertTriangle className="h-3 w-3 text-retry" aria-hidden />
                {t('testConsole.noMatchingSubs')}
              </p>
            ) : (
              <ul className="space-y-1.5">
                {matchingSubscriptions.map((sub) => (
                  <li key={sub.id} className="flex items-center justify-between gap-2 text-xs">
                    <span className="flex min-w-0 items-center gap-1.5">
                      <ArrowRight className="h-3 w-3 flex-shrink-0 text-muted-foreground" aria-hidden />
                      <span className="truncate font-mono">{getEndpointUrl(sub.endpointId)}</span>
                    </span>
                    <Badge variant={sub.enabled ? 'ok' : 'idle'} className="flex-shrink-0">
                      {sub.enabled ? t('common.on') : t('common.off')}
                    </Badge>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </WorkbenchPanel>

      <PermissionGate allowed={canManageEndpoints}>
        <VerificationGate>
          <RunControl
            type="submit"
            label={t('testConsole.runTest')}
            runningLabel={t('testConsole.sending')}
            running={sending}
            disabled={!!jsonError || !eventType.trim()}
            hint={t('testConsole.runHint')}
          />
        </VerificationGate>
      </PermissionGate>
    </form>
  );

  const pingInput = (
    <div className="space-y-4">
      {modeSwitch}

      <WorkbenchPanel
        eyebrow={t('testConsole.inputEyebrow')}
        title={t('testConsole.pingEndpoint')}
        description={t('testConsole.pingDesc')}
      >
        <div className="space-y-1.5">
          <Label htmlFor="tc-endpoint" className="text-xs">{t('testConsole.selectEndpoint')}</Label>
          <Select
            id="tc-endpoint"
            value={selectedEndpointId}
            onChange={(e) => setSelectedEndpointId(e.target.value)}
            disabled={pinging}
          >
            <option value="">{t('testConsole.chooseEndpoint')}</option>
            {endpoints.map((ep) => (
              <option key={ep.id} value={ep.id}>{ep.url}</option>
            ))}
          </Select>
        </div>
      </WorkbenchPanel>

      <PermissionGate allowed={canManageEndpoints}>
        <VerificationGate>
          <RunControl
            icon={Zap}
            label={t('testConsole.runPing')}
            runningLabel={t('testConsole.pinging')}
            running={pinging}
            disabled={!selectedEndpointId}
            onClick={handlePingEndpoint}
            hint={t('testConsole.pingRunHint')}
          />
        </VerificationGate>
      </PermissionGate>
    </div>
  );

  const verifyInput = (
    <form onSubmit={handleVerify} className="space-y-4">
      {modeSwitch}

      <WorkbenchPanel
        eyebrow={t('testConsole.inputEyebrow')}
        title={t('testConsole.verifySignature')}
        description={t('testConsole.verifyDesc')}
        bodyClassName="space-y-4"
      >
        <div className="space-y-1.5">
          <Label htmlFor="tc-secret" className="text-xs">{t('testConsole.verifySecret')}</Label>
          <Input
            id="tc-secret"
            type="password"
            placeholder="whsec_…"
            value={secret}
            onChange={(e) => { setSecret(e.target.value); setVerifyResult(null); }}
            required
            className="font-mono"
          />
          <p className="text-[11px] text-muted-foreground">{t('testConsole.verifySecretHint')}</p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="tc-sig" className="text-xs">{t('testConsole.verifyHeader')}</Label>
          <Input
            id="tc-sig"
            placeholder="v1,1710000000.a1b2c3…"
            value={signatureHeader}
            onChange={(e) => { setSignatureHeader(e.target.value); setVerifyResult(null); }}
            required
            className="font-mono text-xs"
          />
          <p className="text-[11px] text-muted-foreground">{t('testConsole.verifyHeaderHint')}</p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="tc-body" className="text-xs">{t('testConsole.verifyBody')}</Label>
          <Textarea
            id="tc-body"
            value={signedBody}
            onChange={(e) => { setSignedBody(e.target.value); setVerifyResult(null); }}
            required
            rows={8}
            className="font-mono text-xs"
            placeholder='{"type":"user.created","data":{}}'
          />
          <p className="text-[11px] text-muted-foreground">{t('testConsole.verifyBodyHint')}</p>
        </div>
      </WorkbenchPanel>

      <RunControl
        type="submit"
        icon={ShieldCheck}
        label={t('testConsole.runVerify')}
        runningLabel={t('testConsole.verifying')}
        running={verifying}
        disabled={!secret || !signatureHeader || !signedBody}
        hint={t('testConsole.verifyRunHint')}
      />
    </form>
  );

  const input = mode === 'event' ? eventInput : mode === 'ping' ? pingInput : verifyInput;

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={project.name}
        title={t('testConsole.title')}
        description={t('testConsole.description')}
      />

      <Workbench
        input={input}
        result={mode === 'verify' ? (
          <VerifyResult result={verifyResult} running={verifying} />
        ) : (
          <ResultsPanel
            mode={mode}
            lastEvent={lastEvent}
            deliveries={deliveries}
            pingResult={pingResult}
            busy={busy}
            polling={polling}
            expandedDelivery={expandedDelivery}
            setExpandedDelivery={setExpandedDelivery}
            getEndpointUrl={getEndpointUrl}
            copiedId={copiedId}
            copyText={copyText}
          />
        )}
      />
    </div>
  );
}

// ── Signature verdict ──────────────────────────────────────────────

function VerifyResult({ result, running }: { result: boolean | null; running: boolean }) {
  const { t } = useTranslation();

  if (result === null || running) {
    return (
      <ResultPlaceholder
        icon={ShieldCheck}
        title={t('testConsole.verifyEmptyTitle')}
        hint={t('testConsole.verifyEmptyDesc')}
      />
    );
  }

  return (
    <ResultFrame
      kind={result ? 'ok' : 'halt'}
      statusLabel={result ? t('testConsole.verifyValid') : t('testConsole.verifyInvalid')}
      title={t('testConsole.verifySignature')}
    >
      <p className="text-sm text-muted-foreground">
        {result ? t('testConsole.verifyValidDesc') : t('testConsole.verifyInvalidDesc')}
      </p>
    </ResultFrame>
  );
}

// ── Result ─────────────────────────────────────────────────────────

function ResultsPanel({
  mode, lastEvent, deliveries, pingResult, busy, polling,
  expandedDelivery, setExpandedDelivery, getEndpointUrl, copiedId, copyText,
}: {
  mode: ConsoleMode;
  lastEvent: EventResponse | null;
  deliveries: DeliveryWithAttempts[];
  pingResult: EndpointTestResponse | null;
  busy: boolean;
  polling: boolean;
  expandedDelivery: string | null;
  setExpandedDelivery: (id: string | null) => void;
  getEndpointUrl: (id: string) => string;
  copiedId: string | null;
  copyText: (text: string, id: string) => void;
}) {
  const { t } = useTranslation();

  if (!lastEvent && !pingResult && !busy) {
    return (
      <ResultPlaceholder
        icon={Play}
        title={t('testConsole.emptyTitle')}
        hint={t('testConsole.emptyDesc')}
      />
    );
  }

  if (busy && !lastEvent && !pingResult) {
    return (
      <div className="min-h-[320px] space-y-3 rounded-xl border border-dashed border-rail p-4" aria-busy="true">
        <p className="text-sm text-muted-foreground">{t('testConsole.processing')}</p>
        <SkeletonRows count={3} height="h-20" />
      </div>
    );
  }

  if (mode === 'ping' && pingResult) {
    return (
      <ResultFrame
        kind={pingResult.success ? 'ok' : 'halt'}
        statusLabel={pingResult.success ? t('testConsole.pass') : t('testConsole.fail')}
        title={t('testConsole.pingResult')}
        metrics={
          <>
            <ResultMetric label={t('testConsole.status')} value={pingResult.httpStatusCode ?? '—'} />
            <ResultMetric label={t('testConsole.latency')} value={pingResult.latencyMs} unit="ms" />
            <ResultMetric
              label={t('testConsole.result')}
              value={pingResult.success ? t('testConsole.pass') : t('testConsole.fail')}
            />
          </>
        }
      >
        {pingResult.message && (
          <div className="rounded-lg border border-rail bg-muted/40 p-3">
            <p className="mono-label mb-1">{t('testConsole.message')}</p>
            <p className="text-sm">{pingResult.message}</p>
          </div>
        )}
        {pingResult.responseBody && (
          <OutputBlock
            label={t('testConsole.responseBody')}
            actions={
              <CopyButton
                id="ping-body"
                content={pingResult.responseBody}
                label={t('testConsole.responseBody')}
                copiedId={copiedId}
                copyText={copyText}
              />
            }
          >
            <pre className="max-h-48 overflow-auto whitespace-pre-wrap p-2.5 font-mono text-[11px]">
              {formatJson(pingResult.responseBody)}
            </pre>
          </OutputBlock>
        )}
        {pingResult.errorMessage && (
          <div className="rounded-lg border border-halt/30 bg-halt-soft p-3">
            <p className="mono-label mb-1">{t('testConsole.error')}</p>
            <p className="text-sm text-halt">{pingResult.errorMessage}</p>
          </div>
        )}
      </ResultFrame>
    );
  }

  if (!lastEvent) return null;

  const succeeded = deliveries.filter((d) => d.status === 'SUCCESS').length;
  const attemptTotal = deliveries.reduce((sum, d) => sum + d.attemptCount, 0);
  const kind = polling ? 'retry' : rollUp(deliveries);
  const statusLabel = polling
    ? t('testConsole.verdictRunning')
    : deliveries.length === 0
      ? t('testConsole.verdictNoDeliveries')
      : kind === 'ok'
        ? t('testConsole.verdictDelivered')
        : kind === 'halt'
          ? t('testConsole.verdictAbandoned')
          : t('testConsole.verdictPending');

  return (
    <ResultFrame
      kind={kind}
      statusLabel={statusLabel}
      title={t('testConsole.eventCreated')}
      actions={polling ? <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" aria-hidden /> : undefined}
      metrics={
        <>
          <ResultMetric label={t('testConsole.deliveriesCreated')} value={lastEvent.deliveriesCreated ?? 0} />
          <ResultMetric label={t('testConsole.succeeded')} value={succeeded} />
          <ResultMetric label={t('testConsole.attempts')} value={attemptTotal} />
        </>
      }
    >
      <div className="flex items-center gap-2 text-xs">
        <span className="mono-label">{t('testConsole.eventId')}</span>
        <code className="min-w-0 flex-1 truncate font-mono text-[11px]">{lastEvent.id}</code>
        <CopyButton
          id="event-id"
          content={lastEvent.id}
          label={t('testConsole.eventId')}
          copiedId={copiedId}
          copyText={copyText}
        />
      </div>

      {deliveries.length > 0 ? (
        <div className="space-y-2">
          <p className="mono-label">{t('testConsole.deliveryResults')}</p>
          {deliveries.map((d) => (
            <DeliveryCard
              key={d.id}
              delivery={d}
              expanded={expandedDelivery === d.id}
              onToggle={() => setExpandedDelivery(expandedDelivery === d.id ? null : d.id)}
              getEndpointUrl={getEndpointUrl}
              copiedId={copiedId}
              copyText={copyText}
            />
          ))}
        </div>
      ) : !polling && (
        <div className="flex items-start gap-2.5 rounded-lg border border-rail bg-muted/40 p-3">
          <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-retry" aria-hidden />
          <div>
            <p className="text-sm font-medium">{t('testConsole.noDeliveries')}</p>
            <p className="text-xs text-muted-foreground">{t('testConsole.noDeliveriesDesc')}</p>
          </div>
        </div>
      )}
    </ResultFrame>
  );
}

// ── One delivery ───────────────────────────────────────────────────

function DeliveryCard({
  delivery, expanded, onToggle, getEndpointUrl, copiedId, copyText,
}: {
  delivery: DeliveryWithAttempts;
  expanded: boolean;
  onToggle: () => void;
  getEndpointUrl: (id: string) => string;
  copiedId: string | null;
  copyText: (text: string, id: string) => void;
}) {
  const { t } = useTranslation();
  const url = delivery.endpointUrl || getEndpointUrl(delivery.endpointId);
  const kind = kindOfDeliveryStatus(delivery.status);
  const latestAttempt = delivery.attempts?.[delivery.attempts.length - 1];
  const ticks = railTicks(delivery);

  return (
    <div className="overflow-hidden rounded-lg border border-rail">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        className="flex w-full items-center gap-3 p-3 text-left transition-colors hover:bg-secondary/50"
      >
        <div className="min-w-0 flex-1 space-y-1.5">
          <div className="flex items-center gap-2">
            <StatusBadge kind={kind} label={t(labelKeyOfStatus(delivery.status))} />
            <span className="truncate font-mono text-xs">{url}</span>
          </div>
          <div className="flex items-center gap-3 text-[11px] text-muted-foreground">
            {latestAttempt?.httpStatusCode != null && (
              <span className="font-mono text-foreground">HTTP {latestAttempt.httpStatusCode}</span>
            )}
            {latestAttempt?.durationMs != null && (
              <span className="flex items-center gap-0.5 font-mono">
                <Timer className="h-2.5 w-2.5" aria-hidden />
                {latestAttempt.durationMs}ms
              </span>
            )}
            <span className="font-mono">{delivery.attemptCount}/{delivery.maxAttempts}</span>
            <span>{t('testConsole.attempts')}</span>
          </div>
          {ticks.length > 0 && (
            <AttemptRail
              attempts={ticks}
              maxAttempts={delivery.maxAttempts}
              ariaLabel={t('testConsole.attemptRailLabel', { url })}
            />
          )}
        </div>
        {expanded
          ? <ChevronDown className="h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />
          : <ChevronRight className="h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />}
      </button>

      {expanded && (
        <div className="space-y-3 border-t border-rail bg-muted/30 p-3">
          {delivery.attempts && delivery.attempts.length > 0 ? (
            delivery.attempts.map((attempt) => (
              <AttemptDetail key={attempt.id} attempt={attempt} copiedId={copiedId} copyText={copyText} />
            ))
          ) : (
            <p className="text-center text-xs text-muted-foreground">{t('testConsole.noAttempts')}</p>
          )}
        </div>
      )}
    </div>
  );
}

function AttemptDetail({
  attempt, copiedId, copyText,
}: {
  attempt: DeliveryAttemptResponse;
  copiedId: string | null;
  copyText: (text: string, id: string) => void;
}) {
  const { t } = useTranslation();
  const [showRequest, setShowRequest] = useState(false);
  const [showResponse, setShowResponse] = useState(true);

  const isSuccess = attempt.httpStatusCode != null && attempt.httpStatusCode >= 200 && attempt.httpStatusCode < 300;

  return (
    <div className="space-y-2 rounded-lg border border-rail bg-card p-3">
      <div className="flex items-center gap-2 text-xs">
        <StatusBadge
          kind={isSuccess ? 'ok' : attempt.httpStatusCode != null || attempt.errorMessage ? 'halt' : 'idle'}
          label={t('testConsole.attemptNumber', { number: attempt.attemptNumber })}
        />
        {attempt.httpStatusCode != null && (
          <span className="font-mono font-medium">{attempt.httpStatusCode}</span>
        )}
        {attempt.durationMs != null && (
          <span className="flex items-center gap-0.5 font-mono text-muted-foreground">
            <Timer className="h-3 w-3" aria-hidden /> {attempt.durationMs}ms
          </span>
        )}
      </div>

      {attempt.errorMessage && (
        <p className="rounded border border-halt/30 bg-halt-soft px-2.5 py-1.5 text-xs text-halt">
          {attempt.errorMessage}
        </p>
      )}

      <div className="flex gap-1">
        <TogglePill active={showRequest} onClick={() => setShowRequest(!showRequest)} label={t('testConsole.request')} />
        <TogglePill active={showResponse} onClick={() => setShowResponse(!showResponse)} label={t('testConsole.response')} />
      </div>

      {showRequest && (
        <div className="space-y-2">
          {attempt.requestHeaders && (
            <CodeBlock label={t('testConsole.requestHeaders')} content={formatJson(attempt.requestHeaders)} id={`req-h-${attempt.id}`} copiedId={copiedId} copyText={copyText} />
          )}
          {attempt.requestBody && (
            <CodeBlock label={t('testConsole.requestBody')} content={formatJson(attempt.requestBody)} id={`req-b-${attempt.id}`} copiedId={copiedId} copyText={copyText} />
          )}
        </div>
      )}

      {showResponse && (
        <div className="space-y-2">
          {attempt.responseHeaders && (
            <CodeBlock label={t('testConsole.responseHeaders')} content={formatJson(attempt.responseHeaders)} id={`res-h-${attempt.id}`} copiedId={copiedId} copyText={copyText} />
          )}
          {attempt.responseBody && (
            <CodeBlock label={t('testConsole.responseBody')} content={formatJson(attempt.responseBody)} id={`res-b-${attempt.id}`} copiedId={copiedId} copyText={copyText} />
          )}
        </div>
      )}
    </div>
  );
}

function TogglePill({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'rounded px-2 py-1 text-[11px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        active ? 'bg-accent font-medium text-accent-foreground' : 'text-muted-foreground hover:text-foreground',
      )}
    >
      {label}
    </button>
  );
}

function CopyButton({
  id, content, label, copiedId, copyText,
}: {
  id: string;
  content: string;
  label: string;
  copiedId: string | null;
  copyText: (text: string, id: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <Button
      variant="ghost"
      size="icon-sm"
      onClick={() => copyText(content, id)}
      aria-label={t('testConsole.copyContent', { label })}
    >
      {copiedId === id ? <Check className="h-3.5 w-3.5 text-ok" /> : <Copy className="h-3.5 w-3.5" />}
    </Button>
  );
}

function CodeBlock({
  label, content, id, copiedId, copyText,
}: {
  label: string;
  content: string;
  id: string;
  copiedId: string | null;
  copyText: (text: string, id: string) => void;
}) {
  return (
    <OutputBlock
      label={label}
      actions={<CopyButton id={id} content={content} label={label} copiedId={copiedId} copyText={copyText} />}
    >
      <pre className="max-h-40 overflow-auto whitespace-pre-wrap p-2.5 font-mono text-[11px]">{content}</pre>
    </OutputBlock>
  );
}

