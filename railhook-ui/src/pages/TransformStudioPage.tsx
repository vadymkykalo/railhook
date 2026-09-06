import { useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Play, Copy, RotateCcw, Download, Zap, Send, Globe, Shield, Wand2, X,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import { formatJson } from '../lib/json';
import { useTransformPreview, useTransformations, useEvents, useEndpoints, useDeliveryDryRun } from '../api/queries';
import PageHeader from '../components/PageHeader';
import { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import JsonEditor from '../components/JsonEditor';
import {
  Workbench, WorkbenchPanel, RunControl, ResultFrame, ResultMetric,
  ResultPlaceholder, OutputBlock, ModeSwitch,
} from '../components/Workbench';
import type { StatusKind } from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import type { DeliveryDryRunResponse } from '../api/transform.api';

const SAMPLE_PAYLOAD = JSON.stringify({
  event: 'order.completed',
  data: {
    orderId: 'ord_12345',
    customer: { name: 'Jane Doe', email: 'jane@example.com' },
    items: [
      { sku: 'SKU-001', name: 'Widget', qty: 2, price: 19.99 },
      { sku: 'SKU-002', name: 'Gadget', qty: 1, price: 49.99 },
    ],
    total: 89.97,
    currency: 'USD',
  },
  metadata: { source: 'checkout-v2', region: 'us-east-1' },
}, null, 2);

const HINT_EXPRESSIONS = [
  { expr: '$.data', descKey: 'transform.hints.extractData' },
  { expr: '$.data.items', descKey: 'transform.hints.extractItems' },
  { expr: '$.data.customer', descKey: 'transform.hints.extractCustomer' },
  { expr: '$.metadata', descKey: 'transform.hints.extractMetadata' },
  { expr: '$', descKey: 'transform.hints.passThrough' },
];

type StudioMode = 'preview' | 'dryRun';

/** Two JSON documents are "the same" when they parse to the same value. */
function isUnchanged(input: string, output: string): boolean {
  try {
    return JSON.stringify(JSON.parse(input)) === JSON.stringify(JSON.parse(output));
  } catch {
    return input.trim() === output.trim();
  }
}

export default function TransformStudioPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();

  const [mode, setMode] = useState<StudioMode>('preview');
  const [inputPayload, setInputPayload] = useState(SAMPLE_PAYLOAD);
  const [transformExpr, setTransformExpr] = useState('');
  const [customHeaders, setCustomHeaders] = useState('');
  const [outputPayload, setOutputPayload] = useState<string | null>(null);
  const [outputHeaders, setOutputHeaders] = useState<string | null>(null);
  const [errors, setErrors] = useState<string[]>([]);
  const [ran, setRan] = useState(false);
  const [selectedTransformationId, setSelectedTransformationId] = useState('');
  const [showEventPicker, setShowEventPicker] = useState(false);
  const [eventSearch, setEventSearch] = useState('');
  const [eventPageSize, setEventPageSize] = useState(10);
  const [dryRunEndpointId, setDryRunEndpointId] = useState('');
  const [dryRunEventType, setDryRunEventType] = useState('order.completed');
  const [dryRunResult, setDryRunResult] = useState<DeliveryDryRunResponse | null>(null);

  const preview = useTransformPreview(projectId!);
  const dryRun = useDeliveryDryRun(projectId!);
  const { data: transformations = [] } = useTransformations(projectId!);
  const { data: endpoints = [] } = useEndpoints(projectId);
  const {
    data: recentEventsData, isLoading: eventsLoading, isError: eventsFailed,
    error: eventsError, refetch: refetchEvents, isRefetching: eventsRefetching,
  } = useEvents(projectId, 0, eventPageSize, 'createdAt,desc', eventSearch || undefined);
  const recentEvents = recentEventsData?.content ?? [];
  const hasMoreEvents = recentEventsData ? !recentEventsData.last : false;

  const isJsonTemplate = () => transformExpr.trim().startsWith('{') || transformExpr.trim().startsWith('[');

  const handleRun = async () => {
    setErrors([]);
    setOutputPayload(null);
    setOutputHeaders(null);
    try {
      const result = await preview.mutateAsync({
        inputPayload,
        customHeaders: customHeaders || undefined,
        transformationId: selectedTransformationId || undefined,
        template: !selectedTransformationId && isJsonTemplate() ? transformExpr : undefined,
        transformExpression: !selectedTransformationId && !isJsonTemplate() && transformExpr ? transformExpr : undefined,
      });
      setOutputPayload(result.outputPayload);
      setOutputHeaders(result.outputHeaders);
      setErrors(result.errors);
      setRan(true);
    } catch (err: any) {
      showApiError(err, 'transform.previewFailed');
    }
  };

  const handleDryRun = async () => {
    setDryRunResult(null);
    try {
      const result = await dryRun.mutateAsync({
        payload: inputPayload,
        transformationId: selectedTransformationId || undefined,
        payloadTemplate: !selectedTransformationId && isJsonTemplate() ? transformExpr : undefined,
        customHeaders: customHeaders || undefined,
        endpointId: dryRunEndpointId || undefined,
        eventType: dryRunEventType || undefined,
      });
      setDryRunResult(result);
    } catch (err: any) {
      showApiError(err, 'transform.dryRunFailed');
    }
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    showSuccess(t('common.copied'));
  };

  const handleReset = () => {
    setInputPayload(SAMPLE_PAYLOAD);
    setTransformExpr('');
    setCustomHeaders('');
    setOutputPayload(null);
    setOutputHeaders(null);
    setErrors([]);
    setRan(false);
    setDryRunResult(null);
    setSelectedTransformationId('');
  };

  const handleLoadEvent = (payload: string) => {
    setInputPayload(formatJson(payload));
    setShowEventPicker(false);
  };

  const input = (
    <div className="space-y-4">
      <ModeSwitch<StudioMode>
        value={mode}
        onChange={setMode}
        ariaLabel={t('transform.modeLabel')}
        options={[
          { value: 'preview', label: t('transform.modePreview'), icon: Wand2 },
          { value: 'dryRun', label: t('transform.modeDryRun'), icon: Zap },
        ]}
      />

      <WorkbenchPanel
        eyebrow={t('transform.inputEyebrow')}
        title={t('transform.inputPayload')}
        actions={
          <>
            <Button variant="ghost" size="sm" onClick={() => setShowEventPicker(!showEventPicker)}>
              <Download className="h-3.5 w-3.5" /> {t('transform.loadEvent')}
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setInputPayload(formatJson(inputPayload))}>
              {t('transform.format')}
            </Button>
            <Button variant="ghost" size="icon-sm" onClick={() => handleCopy(inputPayload)} title={t('common.copy')} aria-label={t('common.copy')}>
              <Copy className="h-3.5 w-3.5" />
            </Button>
          </>
        }
        bodyClassName="space-y-3"
      >
        {showEventPicker && (
          <div className="space-y-2 rounded-lg border border-rail bg-muted/30 p-3">
            <div className="flex items-center justify-between">
              <p className="mono-label">{t('transform.recentEvents')}</p>
              <Button variant="ghost" size="icon-sm" onClick={() => setShowEventPicker(false)} title={t('common.close')} aria-label={t('common.close')}>
                <X className="h-3.5 w-3.5" />
              </Button>
            </div>
            <Input
              className="h-8 font-mono text-xs"
              placeholder={t('transform.searchEvents')}
              value={eventSearch}
              onChange={(e) => { setEventSearch(e.target.value); setEventPageSize(10); }}
            />
            {eventsLoading ? (
              <SkeletonRows count={3} height="h-11" />
            ) : eventsFailed ? (
              <ErrorState
                error={eventsError}
                onRetry={() => refetchEvents()}
                retrying={eventsRefetching}
                className="flex flex-col items-center justify-center py-6"
              />
            ) : recentEvents.length === 0 ? (
              <EmptyState
                icon={Send}
                title={t('transform.noEvents')}
                className="flex flex-col items-center justify-center py-6"
              />
            ) : (
              <div className="max-h-[220px] space-y-1 overflow-y-auto">
                {recentEvents.map((evt) => (
                  <button
                    key={evt.id}
                    type="button"
                    className="w-full rounded-md border border-transparent px-2.5 py-2 text-left text-xs transition-colors hover:border-rail hover:bg-secondary"
                    onClick={() => handleLoadEvent(evt.payload)}
                  >
                    <span className="flex items-center justify-between gap-2">
                      <span className="truncate font-mono font-medium">{evt.eventType}</span>
                      <span className="flex-shrink-0 font-mono text-[10px] text-muted-foreground">
                        {new Date(evt.createdAt).toLocaleString()}
                      </span>
                    </span>
                    <span className="mt-0.5 block truncate font-mono text-[10px] text-muted-foreground">
                      {evt.payload?.substring(0, 100)}
                    </span>
                  </button>
                ))}
                {hasMoreEvents && (
                  <Button variant="ghost" size="sm" className="w-full" onClick={() => setEventPageSize((s) => s + 10)}>
                    {t('transform.loadMore')}
                  </Button>
                )}
              </div>
            )}
          </div>
        )}

        <JsonEditor
          value={inputPayload}
          onChange={setInputPayload}
          placeholder='{"key": "value"}'
          minHeight="220px"
          maxHeight="320px"
        />
      </WorkbenchPanel>

      <WorkbenchPanel
        eyebrow={t('transform.transformEyebrow')}
        title={t('transform.expression')}
        bodyClassName="space-y-3"
      >
        <div className="flex flex-wrap gap-1.5">
          {HINT_EXPRESSIONS.map((h) => (
            <button
              key={h.expr}
              type="button"
              title={t(h.descKey)}
              onClick={() => setTransformExpr(h.expr)}
              className="inline-flex items-center gap-1.5 rounded-md border border-rail px-2 py-1 text-[11px] transition-colors hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <code className="font-mono text-primary">{h.expr}</code>
              <span className="text-muted-foreground">{t(h.descKey)}</span>
            </button>
          ))}
        </div>

        {transformations.length > 0 && (
          <div className="space-y-1.5">
            <Label className="text-xs">{t('transform.savedTransformation')}</Label>
            <Select
              value={selectedTransformationId}
              onChange={(e) => {
                const id = e.target.value;
                setSelectedTransformationId(id);
                const tr = transformations.find((item) => item.id === id);
                if (tr) setTransformExpr(formatJson(tr.template));
              }}
            >
              <option value="">{t('transform.noSavedTransformation')}</option>
              {transformations.filter((tr) => tr.enabled).map((tr) => (
                <option key={tr.id} value={tr.id}>{`${tr.name} v${tr.version}`}</option>
              ))}
            </Select>
          </div>
        )}

        <JsonEditor
          value={transformExpr}
          onChange={(val) => { setTransformExpr(val); if (selectedTransformationId) setSelectedTransformationId(''); }}
          placeholder={'$.data'}
          minHeight="90px"
          maxHeight="200px"
        />

        <div className="space-y-1.5">
          <Label htmlFor="ts-headers" className="text-xs">{t('transform.customHeaders')}</Label>
          <Input
            id="ts-headers"
            className="font-mono text-sm"
            value={customHeaders}
            onChange={(e) => setCustomHeaders(e.target.value)}
            placeholder='{"X-Custom": "value"}'
          />
        </div>

        {mode === 'dryRun' && (
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label className="text-xs">{t('transform.dryRunEndpoint')}</Label>
              <Select value={dryRunEndpointId} onChange={(e) => setDryRunEndpointId(e.target.value)}>
                <option value="">{t('transform.dryRunNoEndpoint')}</option>
                {endpoints.filter((ep) => ep.enabled).map((ep) => (
                  <option key={ep.id} value={ep.id}>{ep.url}</option>
                ))}
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="ts-dry-type" className="text-xs">{t('transform.dryRunEventType')}</Label>
              <Input
                id="ts-dry-type"
                className="font-mono text-xs"
                value={dryRunEventType}
                onChange={(e) => setDryRunEventType(e.target.value)}
                placeholder="order.created"
              />
            </div>
          </div>
        )}
      </WorkbenchPanel>

      {mode === 'preview' ? (
        <RunControl
          label={t('transform.run')}
          runningLabel={t('transform.running')}
          running={preview.isPending}
          disabled={!inputPayload}
          onClick={handleRun}
          hint={t('transform.runHint')}
          secondary={
            <Button variant="outline" size="lg" onClick={handleReset}>
              <RotateCcw className="h-4 w-4" /> {t('transform.reset')}
            </Button>
          }
        />
      ) : (
        <RunControl
          icon={Send}
          label={t('transform.dryRunBtn')}
          runningLabel={t('transform.dryRunRunning')}
          running={dryRun.isPending}
          disabled={!inputPayload}
          onClick={handleDryRun}
          hint={t('transform.dryRunDesc')}
          secondary={
            <Button variant="outline" size="lg" onClick={handleReset}>
              <RotateCcw className="h-4 w-4" /> {t('transform.reset')}
            </Button>
          }
        />
      )}
    </div>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader title={t('transform.title')} description={t('transform.subtitle')} />
      <Workbench
        input={input}
        result={mode === 'preview'
          ? <PreviewResult
              ran={ran}
              running={preview.isPending}
              inputPayload={inputPayload}
              outputPayload={outputPayload}
              outputHeaders={outputHeaders}
              errors={errors}
              onCopy={handleCopy}
            />
          : <DryRunResult result={dryRunResult} running={dryRun.isPending} onCopy={handleCopy} />}
      />
    </div>
  );
}

// ── Preview result ─────────────────────────────────────────────────

function PreviewResult({
  ran, running, inputPayload, outputPayload, outputHeaders, errors, onCopy,
}: {
  ran: boolean;
  running: boolean;
  inputPayload: string;
  outputPayload: string | null;
  outputHeaders: string | null;
  errors: string[];
  onCopy: (text: string) => void;
}) {
  const { t } = useTranslation();

  if (!ran || running) {
    return (
      <ResultPlaceholder
        icon={Play}
        title={t('transform.emptyTitle')}
        hint={running ? t('transform.running') : t('transform.noOutput')}
      />
    );
  }

  const unchanged = !!outputPayload && isUnchanged(inputPayload, outputPayload);
  // A transform that ran clean but changed nothing is not a success worth a
  // green badge — nothing happened. That is `idle`.
  const kind: StatusKind = errors.length > 0 ? 'halt' : unchanged ? 'idle' : 'ok';
  const statusLabel = errors.length > 0
    ? t('transform.verdictErrors')
    : unchanged ? t('transform.verdictUnchanged') : t('transform.verdictChanged');

  const headerCount = (() => {
    if (!outputHeaders) return 0;
    try { return Object.keys(JSON.parse(outputHeaders)).length; } catch { return 0; }
  })();

  return (
    <ResultFrame
      kind={kind}
      statusLabel={statusLabel}
      title={t('transform.outputPayload')}
      actions={outputPayload ? (
        <Button variant="ghost" size="icon-sm" onClick={() => onCopy(outputPayload)} title={t('common.copy')} aria-label={t('common.copy')}>
          <Copy className="h-3.5 w-3.5" />
        </Button>
      ) : undefined}
      metrics={
        <>
          <ResultMetric label={t('transform.metricOutputSize')} value={outputPayload?.length ?? 0} unit="B" />
          <ResultMetric label={t('transform.metricHeaders')} value={headerCount} />
          <ResultMetric label={t('transform.metricErrors')} value={errors.length} />
        </>
      }
    >
      {outputPayload && (
        <JsonEditor value={outputPayload} readOnly minHeight="260px" maxHeight="380px" />
      )}

      {outputHeaders && (
        <OutputBlock label={t('transform.outputHeaders')}>
          <pre className="max-h-40 overflow-auto whitespace-pre-wrap p-2.5 font-mono text-[11px]">{outputHeaders}</pre>
        </OutputBlock>
      )}

      {errors.length > 0 && (
        <div className="rounded-lg border border-halt/30 bg-halt-soft p-3">
          <p className="mono-label mb-1.5">{t('transform.errors')}</p>
          <ul className="space-y-1">
            {errors.map((err, i) => (
              <li key={i} className="text-xs text-halt">{err}</li>
            ))}
          </ul>
        </div>
      )}
    </ResultFrame>
  );
}

// ── Dry-run result ─────────────────────────────────────────────────

function DryRunResult({
  result, running, onCopy,
}: {
  result: DeliveryDryRunResponse | null;
  running: boolean;
  onCopy: (text: string) => void;
}) {
  const { t } = useTranslation();

  if (!result || running) {
    return (
      <ResultPlaceholder
        icon={Zap}
        title={t('transform.dryRunEmptyTitle')}
        hint={running ? t('transform.dryRunRunning') : t('transform.dryRunEmptyDesc')}
      />
    );
  }

  const headerCount = result.requestHeaders ? Object.keys(result.requestHeaders).length : 0;
  const errorCount = result.errors?.length ?? 0;

  return (
    <ResultFrame
      kind={result.success ? 'ok' : 'halt'}
      statusLabel={result.success ? t('transform.verdictSimulated') : t('transform.verdictErrors')}
      title={t('transform.dryRunResult')}
      metrics={
        <>
          <ResultMetric
            label={t('transform.metricSigned')}
            value={result.signature ? t('common.yes') : t('common.no')}
          />
          <ResultMetric label={t('transform.metricHeaders')} value={headerCount} />
          <ResultMetric label={t('transform.metricErrors')} value={errorCount} />
        </>
      }
    >
      {result.endpointUrl && (
        <div className="flex items-center gap-2 text-xs">
          <Globe className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />
          <span className="mono-label">POST</span>
          <code className="min-w-0 flex-1 truncate rounded bg-muted px-1.5 py-0.5 font-mono text-primary">{result.endpointUrl}</code>
        </div>
      )}

      {result.transformationName && (
        <div className="flex items-center gap-2 text-xs">
          <Wand2 className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />
          <span className="mono-label">{t('transform.dryRunTransformation')}</span>
          <span className="font-mono">{`${result.transformationName} v${result.transformationVersion}`}</span>
        </div>
      )}

      {result.signature && (
        <OutputBlock
          label={
            <span className="inline-flex items-center gap-1.5">
              <Shield className="h-3 w-3" aria-hidden />
              {t('transform.dryRunSignature')}
            </span>
          }
          actions={
            <Button variant="ghost" size="icon-sm" onClick={() => onCopy(result.signature!)} title={t('common.copy')} aria-label={t('common.copy')}>
              <Copy className="h-3.5 w-3.5" />
            </Button>
          }
        >
          <code className="block break-all p-2.5 font-mono text-[10px] text-muted-foreground">{result.signature}</code>
        </OutputBlock>
      )}

      {headerCount > 0 && result.requestHeaders && (
        <OutputBlock label={t('transform.dryRunHeaders')}>
          <div className="max-h-[200px] space-y-0.5 overflow-y-auto p-2.5">
            {Object.entries(result.requestHeaders).map(([key, val]) => (
              <div key={key} className="flex gap-2 font-mono text-[11px]">
                <span className="flex-shrink-0 font-medium text-primary">{key}</span>
                <span className="break-all text-muted-foreground">{val}</span>
              </div>
            ))}
          </div>
        </OutputBlock>
      )}

      {result.transformedPayload && (
        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <p className="mono-label">{t('transform.dryRunBody')}</p>
            <Button variant="ghost" size="icon-sm" onClick={() => onCopy(result.transformedPayload!)} title={t('common.copy')} aria-label={t('common.copy')}>
              <Copy className="h-3.5 w-3.5" />
            </Button>
          </div>
          <JsonEditor value={result.transformedPayload} readOnly minHeight="140px" maxHeight="260px" />
        </div>
      )}

      {errorCount > 0 && (
        <div className="rounded-lg border border-halt/30 bg-halt-soft p-3">
          <ul className="space-y-1">
            {result.errors.map((err, i) => (
              <li key={i} className="text-xs text-halt">{err}</li>
            ))}
          </ul>
        </div>
      )}
    </ResultFrame>
  );
}
