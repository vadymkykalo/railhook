import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import {
  Play, Copy, RotateCcw, Download, Zap, Send, Globe, Shield, Wand2, X, Save,
  FileJson, FileOutput, GitCompare, Terminal, Code2, Ban, CheckCircle2,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import { formatJson } from '../lib/json';
import {
  useTransformPreview, useTransformations, useEvents, useEndpoints, useDeliveryDryRun,
  useCreateTransformation, useUpdateTransformation,
} from '../api/queries';
import PageHeader from '../components/PageHeader';
import { SkeletonRows } from '../components/PageSkeleton';
import { ErrorState } from '../components/EmptyState';
import JsonEditor from '../components/JsonEditor';
import ScriptEditor from '../components/editor/ScriptEditor';
import DiffView from '../components/DiffView';
import { Tabs, TabPanel } from '../components/ui/tabs';
import {
  Workbench, WorkbenchPanel, RunControl, ResultFrame, ResultMetric,
  ResultPlaceholder, OutputBlock, ModeSwitch,
} from '../components/Workbench';
import type { StatusKind } from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import type {
  DeliveryDryRunResponse, TransformPreviewResponse, ScriptFailureReason,
} from '../api/transform.api';
import type { TransformationKind, TransformationResponse } from '../types/api.types';

/**
 * The Transform Studio.
 *
 * One loop, and it has to close without leaving the page: edit a script, run it
 * against a real recent event, read the Output, the Diff and the Console side by
 * side, and save it back into the transformation. Every part of that used to be
 * somewhere else — the editor was a JSON box, there was no console because there
 * was nothing to log, and saving meant going to a different page and pasting.
 *
 * What runs here is the engine the worker runs. The preview endpoint and the
 * delivery dry-run both go through `JavaScriptTransformEngine` in
 * `railhook-common`, under the same sandbox and the same limits, so a script
 * that works here is a script that works on a real Delivery. That is the only
 * reason a preview is worth anything.
 */

const SAMPLE_PAYLOAD = JSON.stringify({
  id: 'ord_9001',
  placed_at: '2026-09-20T14:05:09.123Z',
  items: [
    { sku: 'WIDGET', qty: 2, price: 19.99 },
    { sku: 'GADGET', qty: 1, price: 61.5 },
  ],
}, null, 2);

/**
 * What a new script starts as: the three things the template language cannot do,
 * in the order the documentation explains them. An empty editor is a worse
 * starting point than a working example you delete.
 */
const STARTER_SCRIPT = `function handler(webhook) {
  // Reshape an array — a template cannot loop.
  var lines = webhook.payload.items.map(function (item) {
    return { sku: item.sku, total: money(item.qty * item.price) };
  });

  var out = {
    order: webhook.payload.id,
    lines: lines,
    value: money(lines.reduce(function (sum, line) { return sum + line.total; }, 0))
  };

  // Add a field conditionally — a template cannot branch.
  if (out.value >= 100) {
    out.review = 'manual';
  }

  // Format a date — a template cannot compute.
  out.day = new Date(webhook.payload.placed_at).toISOString().slice(0, 10);

  console.log('lines', lines.length, 'value', out.value);

  return { payload: out, headers: { 'X-Order-Value': out.value.toFixed(2) } };
}

function money(amount) {
  return Math.round(amount * 100) / 100;
}
`;

const STARTER_TEMPLATE = `{
  "order": "\${$.id}",
  "placed_at": "\${$.placed_at}"
}`;

type StudioMode = 'preview' | 'dryRun';
type StudioTab = 'input' | 'output' | 'diff' | 'console';

const HINT_EXPRESSIONS = [
  { expr: '$.data', descKey: 'transform.hints.extractData' },
  { expr: '$.data.items', descKey: 'transform.hints.extractItems' },
  { expr: '$.data.customer', descKey: 'transform.hints.extractCustomer' },
  { expr: '$.metadata', descKey: 'transform.hints.extractMetadata' },
  { expr: '$', descKey: 'transform.hints.passThrough' },
];

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
  const [searchParams, setSearchParams] = useSearchParams();

  const [mode, setMode] = useState<StudioMode>('preview');
  const [tab, setTab] = useState<StudioTab>('input');
  const [kind, setKind] = useState<TransformationKind>('JAVASCRIPT');
  const [source, setSource] = useState(STARTER_SCRIPT);
  const [inputPayload, setInputPayload] = useState(SAMPLE_PAYLOAD);
  const [customHeaders, setCustomHeaders] = useState('');
  const [selectedTransformationId, setSelectedTransformationId] = useState('');
  const [savedSource, setSavedSource] = useState<string | null>(null);
  const [showEventPicker, setShowEventPicker] = useState(false);
  const [eventSearch, setEventSearch] = useState('');
  const [eventPageSize, setEventPageSize] = useState(10);
  const [dryRunEndpointId, setDryRunEndpointId] = useState('');
  const [eventType, setEventType] = useState('order.completed');
  const [preview, setPreview] = useState<TransformPreviewResponse | null>(null);
  const [dryRunResult, setDryRunResult] = useState<DeliveryDryRunResponse | null>(null);
  const [saveAsOpen, setSaveAsOpen] = useState(false);
  const [saveAsName, setSaveAsName] = useState('');

  const previewMutation = useTransformPreview(projectId!);
  const dryRunMutation = useDeliveryDryRun(projectId!);
  const createTransformation = useCreateTransformation(projectId!);
  const updateTransformation = useUpdateTransformation(projectId!);
  const { data: transformations = [] } = useTransformations(projectId!);
  const { data: endpoints = [] } = useEndpoints(projectId);
  const {
    data: recentEventsData, isLoading: eventsLoading, isError: eventsFailed,
    error: eventsError, refetch: refetchEvents, isRefetching: eventsRefetching,
  } = useEvents(projectId, 0, eventPageSize, 'createdAt,desc', eventSearch || undefined);
  const recentEvents = recentEventsData?.content ?? [];
  const hasMoreEvents = recentEventsData ? !recentEventsData.last : false;

  const selected: TransformationResponse | undefined =
    transformations.find((item) => item.id === selectedTransformationId);

  const loadTransformation = useCallback((transformation: TransformationResponse) => {
    setSelectedTransformationId(transformation.id);
    setKind(transformation.kind ?? 'TEMPLATE');
    const text = transformation.kind === 'JAVASCRIPT'
      ? transformation.template
      : formatJson(transformation.template);
    setSource(text);
    setSavedSource(text);
    setPreview(null);
    setDryRunResult(null);
  }, []);

  // `?transformation=<id>` is how the Transformations page hands one over, so
  // "edit this script" lands in the editor with the script already in it.
  const appliedFromUrl = useRef<string | null>(null);
  useEffect(() => {
    const wanted = searchParams.get('transformation');
    if (!wanted || wanted === appliedFromUrl.current) return;
    const match = transformations.find((item) => item.id === wanted);
    if (match) {
      appliedFromUrl.current = wanted;
      loadTransformation(match);
    }
  }, [searchParams, transformations, loadTransformation]);

  const dirty = savedSource !== null && savedSource !== source;
  const canSave = source.trim().length > 0;

  /**
   * What to run: the editor, unless the editor is exactly what is saved.
   *
   * Running a saved transformation *by id* is the honest thing to do while you have not touched
   * it — the server resolves it the way a Delivery would, version and all. The moment you edit,
   * it stops being honest: the run would be of the saved script and the editor would be showing
   * you something else, which is the one thing a debug loop must never do.
   */
  const runsSavedById = Boolean(selectedTransformationId) && !dirty;

  const handleSelectTransformation = (id: string) => {
    if (!id) {
      setSelectedTransformationId('');
      setSavedSource(null);
      appliedFromUrl.current = null;
      const next = new URLSearchParams(searchParams);
      next.delete('transformation');
      setSearchParams(next, { replace: true });
      return;
    }
    const match = transformations.find((item) => item.id === id);
    if (match) {
      loadTransformation(match);
      const next = new URLSearchParams(searchParams);
      next.set('transformation', id);
      setSearchParams(next, { replace: true });
    }
  };

  const handleSwitchKind = (next: TransformationKind) => {
    if (next === kind) return;
    setKind(next);
    // Only when the editor still holds the starter for the other language: a
    // switch must never silently eat something somebody wrote.
    if (source.trim() === '' || source === STARTER_SCRIPT || source === STARTER_TEMPLATE) {
      setSource(next === 'JAVASCRIPT' ? STARTER_SCRIPT : STARTER_TEMPLATE);
    }
    setPreview(null);
    setDryRunResult(null);
  };

  const handleRun = useCallback(async () => {
    setDryRunResult(null);
    try {
      const result = await previewMutation.mutateAsync({
        inputPayload,
        kind,
        template: runsSavedById ? undefined : source,
        transformationId: runsSavedById ? selectedTransformationId : undefined,
        customHeaders: customHeaders || undefined,
        eventType,
        url: endpoints.find((endpoint) => endpoint.id === dryRunEndpointId)?.url,
      });
      setPreview(result);
      setTab(result.success ? 'output' : 'console');
    } catch (err) {
      showApiError(err, 'transform.previewFailed');
    }
  }, [previewMutation, inputPayload, kind, runsSavedById, selectedTransformationId, source,
    customHeaders, eventType, endpoints, dryRunEndpointId]);

  const handleDryRun = useCallback(async () => {
    setPreview(null);
    try {
      const result = await dryRunMutation.mutateAsync({
        payload: inputPayload,
        kind,
        payloadTemplate: runsSavedById ? undefined : source,
        transformationId: runsSavedById ? selectedTransformationId : undefined,
        customHeaders: customHeaders || undefined,
        endpointId: dryRunEndpointId || undefined,
        eventType: eventType || undefined,
      });
      setDryRunResult(result);
      setTab(result.success ? 'output' : 'console');
    } catch (err) {
      showApiError(err, 'transform.dryRunFailed');
    }
  }, [dryRunMutation, inputPayload, kind, runsSavedById, selectedTransformationId, source,
    customHeaders, dryRunEndpointId, eventType]);

  const run = mode === 'preview' ? handleRun : handleDryRun;
  const running = previewMutation.isPending || dryRunMutation.isPending;

  const handleSave = useCallback(async () => {
    if (!canSave) return;
    if (!selected) {
      setSaveAsName('');
      setSaveAsOpen(true);
      return;
    }
    try {
      await updateTransformation.mutateAsync({
        id: selected.id,
        data: {
          name: selected.name,
          description: selected.description,
          template: source,
          kind,
          enabled: selected.enabled,
        },
      });
      setSavedSource(source);
      showSuccess(t('transform.saved'));
    } catch (err) {
      showApiError(err, 'transform.saveFailed');
    }
  }, [canSave, selected, updateTransformation, source, kind, t]);

  const handleSaveAs = async () => {
    if (!saveAsName.trim()) return;
    try {
      const created = await createTransformation.mutateAsync({
        name: saveAsName.trim(),
        template: source,
        kind,
        enabled: true,
      });
      setSaveAsOpen(false);
      setSelectedTransformationId(created.id);
      setSavedSource(source);
      const next = new URLSearchParams(searchParams);
      next.set('transformation', created.id);
      setSearchParams(next, { replace: true });
      showSuccess(t('transform.saved'));
    } catch (err) {
      showApiError(err, 'transform.saveFailed');
    }
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    showSuccess(t('common.copied'));
  };

  const handleLoadEvent = (payload: string, type?: string) => {
    setInputPayload(formatJson(payload));
    if (type) setEventType(type);
    setShowEventPicker(false);
    setTab('input');
  };

  // ── what the last run said ──────────────────────────────────────────────
  const outputPayload = mode === 'preview'
    ? preview?.outputPayload ?? null
    : dryRunResult?.transformedPayload ?? null;
  const consoleLines = (mode === 'preview' ? preview?.console : dryRunResult?.console) ?? [];
  const errors = (mode === 'preview' ? preview?.errors : dryRunResult?.errors) ?? [];
  const cancelled = (mode === 'preview' ? preview?.cancelled : dryRunResult?.cancelled) ?? false;
  const cancelReason = (mode === 'preview' ? preview?.cancelReason : dryRunResult?.cancelReason) ?? null;
  const durationMs = (mode === 'preview' ? preview?.durationMs : dryRunResult?.durationMs) ?? 0;
  const errorLine = (mode === 'preview' ? preview?.errorLine : dryRunResult?.errorLine) ?? null;
  const errorReason = (mode === 'preview' ? preview?.errorReason : dryRunResult?.errorReason) ?? null;
  const hasRun = preview !== null || dryRunResult !== null;

  /**
   * The engine speaks English and this UI does not, necessarily. The reason comes back as a
   * value precisely so the sentence can be ours; the engine's own message is kept underneath
   * it, verbatim, because that is where the line number and the thrown message live.
   */
  const failureHeadline = errors.length === 0
    ? null
    : t(`transform.errorReason.${(errorReason ?? 'UNKNOWN') as ScriptFailureReason | 'UNKNOWN'}`, {
      defaultValue: t('transform.errorReason.UNKNOWN'),
    });

  const outputHeaders = useMemo(() => {
    if (mode === 'dryRun') return dryRunResult?.requestHeaders ?? null;
    if (!preview?.outputHeaders) return null;
    try {
      return JSON.parse(preview.outputHeaders) as Record<string, string>;
    } catch {
      return null;
    }
  }, [mode, preview, dryRunResult]);

  const verdict: { kind: StatusKind; label: string } = useMemo(() => {
    if (!hasRun) return { kind: 'idle', label: t('transform.verdictNotRun') };
    if (errors.length > 0) return { kind: 'halt', label: t('transform.verdictErrors') };
    if (cancelled) return { kind: 'idle', label: t('transform.verdictCancelled') };
    if (mode === 'dryRun') return { kind: 'ok', label: t('transform.verdictSimulated') };
    if (outputPayload && isUnchanged(inputPayload, outputPayload)) {
      return { kind: 'idle', label: t('transform.verdictUnchanged') };
    }
    return { kind: 'ok', label: t('transform.verdictChanged') };
  }, [hasRun, errors.length, cancelled, mode, outputPayload, inputPayload, t]);

  const isScript = kind === 'JAVASCRIPT';

  // ── left column: the script ─────────────────────────────────────────────
  const editorColumn = (
    <div className="space-y-4">
      <WorkbenchPanel
        eyebrow={t('transform.transformEyebrow')}
        title={isScript ? t('transform.scriptTitle') : t('transform.templateTitle')}
        actions={
          <>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => handleCopy(source)}
              title={t('common.copy')}
              aria-label={t('common.copy')}
            >
              <Copy className="h-3.5 w-3.5" />
            </Button>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => setSource(isScript ? STARTER_SCRIPT : STARTER_TEMPLATE)}
              title={t('transform.reset')}
              aria-label={t('transform.reset')}
            >
              <RotateCcw className="h-3.5 w-3.5" />
            </Button>
          </>
        }
        bodyClassName="space-y-3 p-4"
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label className="mono-label">{t('transform.savedTransformation')}</Label>
            <Select
              value={selectedTransformationId}
              onChange={(e) => handleSelectTransformation(e.target.value)}
              aria-label={t('transform.savedTransformation')}
            >
              <option value="">{t('transform.noSavedTransformation')}</option>
              {transformations.map((item) => (
                <option key={item.id} value={item.id}>
                  {`${item.name} · ${item.kind === 'JAVASCRIPT'
                    ? t('transform.kindJavascript') : t('transform.kindTemplateShort')}`}
                </option>
              ))}
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label className="mono-label">{t('transform.kindLabel')}</Label>
            <ModeSwitch<TransformationKind>
              value={kind}
              onChange={handleSwitchKind}
              ariaLabel={t('transform.kindLabel')}
              options={[
                { value: 'JAVASCRIPT', label: t('transform.kindJavascript'), icon: Code2 },
                { value: 'TEMPLATE', label: t('transform.kindTemplate'), icon: Wand2 },
              ]}
            />
          </div>
        </div>

        {isScript ? (
          <ScriptEditor
            value={source}
            onChange={setSource}
            onRun={run}
            onSave={handleSave}
            minHeight="340px"
            maxHeight="min(56vh, 620px)"
            errorLine={errorLine}
            errorMessage={failureHeadline}
            aria-label={t('transform.scriptTitle')}
          />
        ) : (
          <>
            <div className="flex flex-wrap gap-1.5">
              {HINT_EXPRESSIONS.map((hint) => (
                <button
                  key={hint.expr}
                  type="button"
                  onClick={() => setSource(hint.expr)}
                  title={t(hint.descKey)}
                  className="border border-rail bg-muted/40 px-2 py-1 font-mono text-[11px] text-muted-foreground transition-colors hover:text-foreground"
                >
                  {hint.expr}
                </button>
              ))}
            </div>
            <JsonEditor
              value={source}
              onChange={setSource}
              minHeight="260px"
              maxHeight="min(48vh, 520px)"
              aria-label={t('transform.templateTitle')}
            />
          </>
        )}

        {isScript && (
          <p className="text-[11px] leading-relaxed text-muted-foreground">
            {t('transform.contractHint')}
          </p>
        )}
      </WorkbenchPanel>

      <WorkbenchPanel eyebrow={t('transform.runEyebrow')} title={t('transform.runAgainst')} bodyClassName="space-y-3 p-4">
        <ModeSwitch<StudioMode>
          value={mode}
          onChange={(next) => { setMode(next); setPreview(null); setDryRunResult(null); }}
          ariaLabel={t('transform.modeLabel')}
          options={[
            { value: 'preview', label: t('transform.modePreview'), icon: Wand2 },
            { value: 'dryRun', label: t('transform.modeDryRun'), icon: Zap },
          ]}
        />
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label className="mono-label">{t('transform.dryRunEndpoint')}</Label>
            <Select
              value={dryRunEndpointId}
              onChange={(e) => setDryRunEndpointId(e.target.value)}
              aria-label={t('transform.dryRunEndpoint')}
            >
              <option value="">{t('transform.dryRunNoEndpoint')}</option>
              {endpoints.map((endpoint) => (
                <option key={endpoint.id} value={endpoint.id}>{endpoint.url}</option>
              ))}
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label className="mono-label">{t('transform.dryRunEventType')}</Label>
            <Input
              className="h-9 font-mono text-xs"
              value={eventType}
              onChange={(e) => setEventType(e.target.value)}
              aria-label={t('transform.dryRunEventType')}
            />
          </div>
        </div>
        <div className="space-y-1.5">
          <Label className="mono-label">{t('transform.customHeaders')}</Label>
          <Input
            className="h-9 font-mono text-xs"
            placeholder='{"X-Custom": "value"}'
            value={customHeaders}
            onChange={(e) => setCustomHeaders(e.target.value)}
            aria-label={t('transform.customHeaders')}
          />
        </div>
      </WorkbenchPanel>

      <RunControl
        label={mode === 'preview' ? t('transform.run') : t('transform.dryRunBtn')}
        runningLabel={mode === 'preview' ? t('transform.running') : t('transform.dryRunRunning')}
        running={running}
        onClick={run}
        icon={mode === 'preview' ? Play : Send}
        hint={t('transform.runShortcutHint')}
        secondary={
          <Button
            variant="outline"
            size="sm"
            onClick={handleSave}
            disabled={!canSave || updateTransformation.isPending || createTransformation.isPending}
          >
            <Save className="h-3.5 w-3.5" />
            {selected ? t('transform.save') : t('transform.saveAs')}
            {dirty && <span className="ml-1 h-1.5 w-1.5 rounded-full bg-primary" aria-hidden="true" />}
          </Button>
        }
      />
    </div>
  );

  // ── right column: four views of one run ─────────────────────────────────
  const resultColumn = (
    <ResultFrame
      kind={verdict.kind}
      statusLabel={verdict.label}
      title={mode === 'preview' ? t('transform.resultTitle') : t('transform.dryRunResult')}
      metrics={
        <>
          <ResultMetric
            label={t('transform.metricDuration')}
            value={hasRun ? durationMs : '—'}
            unit={hasRun ? 'ms' : undefined}
          />
          <ResultMetric
            label={t('transform.metricOutputSize')}
            value={outputPayload ? new Blob([outputPayload]).size : '—'}
            unit={outputPayload ? 'B' : undefined}
          />
          <ResultMetric
            label={t('transform.metricConsole')}
            value={hasRun ? consoleLines.length : '—'}
          />
        </>
      }
      actions={
        outputPayload ? (
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => handleCopy(outputPayload)}
            title={t('common.copy')}
            aria-label={t('common.copy')}
          >
            <Copy className="h-3.5 w-3.5" />
          </Button>
        ) : undefined
      }
    >
      <Tabs<StudioTab>
        value={tab}
        onChange={setTab}
        ariaLabel={t('transform.tabsLabel')}
        tabs={[
          { value: 'input', label: t('transform.tabInput'), icon: FileJson },
          { value: 'output', label: t('transform.tabOutput'), icon: FileOutput },
          { value: 'diff', label: t('transform.tabDiff'), icon: GitCompare },
          {
            value: 'console',
            label: t('transform.tabConsole'),
            icon: Terminal,
            badge: errors.length > 0 ? errors.length : (consoleLines.length || undefined),
            badgeAlarming: errors.length > 0,
          },
        ]}
      />

      <TabPanel value="input" active={tab} className="space-y-3">
        <div className="flex flex-wrap items-center gap-1.5">
          <Button variant="ghost" size="sm" onClick={() => setShowEventPicker(!showEventPicker)}>
            <Download className="h-3.5 w-3.5" /> {t('transform.loadEvent')}
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setInputPayload(formatJson(inputPayload))}>
            {t('transform.format')}
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => handleCopy(inputPayload)}
            title={t('common.copy')}
            aria-label={t('common.copy')}
          >
            <Copy className="h-3.5 w-3.5" />
          </Button>
        </div>

        {showEventPicker && (
          <div className="space-y-2 border border-rail bg-muted/30 p-3">
            <div className="flex items-center justify-between">
              <p className="mono-label">{t('transform.recentEvents')}</p>
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => setShowEventPicker(false)}
                title={t('common.close')}
                aria-label={t('common.close')}
              >
                <X className="h-3.5 w-3.5" />
              </Button>
            </div>
            <Input
              className="h-8 font-mono text-xs"
              placeholder={t('transform.searchEvents')}
              value={eventSearch}
              onChange={(e) => { setEventSearch(e.target.value); setEventPageSize(10); }}
              aria-label={t('transform.searchEvents')}
            />
            {eventsLoading ? (
              <SkeletonRows count={3} height="h-11" />
            ) : eventsFailed ? (
              <ErrorState error={eventsError} onRetry={refetchEvents} retrying={eventsRefetching} />
            ) : recentEvents.length === 0 ? (
              <p className="py-3 text-center text-xs text-muted-foreground">{t('transform.noEvents')}</p>
            ) : (
              <div className="max-h-56 space-y-1 overflow-auto">
                {recentEvents.map((event) => (
                  <button
                    key={event.id}
                    type="button"
                    onClick={() => handleLoadEvent(event.payload, event.eventType)}
                    className="flex w-full items-center justify-between gap-2 border border-rail bg-card px-2.5 py-2 text-left transition-colors hover:border-primary/40"
                  >
                    <span className="truncate font-mono text-[11px]">{event.eventType}</span>
                    <span className="shrink-0 font-mono text-[10px] text-muted-foreground">
                      {new Date(event.createdAt).toLocaleString()}
                    </span>
                  </button>
                ))}
                {hasMoreEvents && (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="w-full"
                    onClick={() => setEventPageSize((size) => size + 10)}
                  >
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
          minHeight="300px"
          maxHeight="min(48vh, 520px)"
          aria-label={t('transform.inputPayload')}
        />
      </TabPanel>

      <TabPanel value="output" active={tab} className="space-y-3">
        {!hasRun ? (
          <ResultPlaceholder icon={Play} title={t('transform.emptyTitle')} hint={t('transform.noOutput')} />
        ) : cancelled ? (
          <div className="border border-rail bg-muted/30 p-5 text-center">
            <Ban className="mx-auto mb-2 h-5 w-5 text-muted-foreground" aria-hidden="true" />
            <p className="text-sm font-medium">{t('transform.cancelledTitle')}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {cancelReason || t('transform.cancelledNoReason')}
            </p>
            <p className="mt-2 text-xs text-muted-foreground">{t('transform.cancelledHint')}</p>
          </div>
        ) : (
          <>
            {mode === 'dryRun' && dryRunResult?.endpointUrl && (
              <div className="flex items-center gap-2 border border-rail bg-muted/30 px-3 py-2">
                <Globe className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                <span className="truncate font-mono text-[11px]">{dryRunResult.endpointUrl}</span>
              </div>
            )}
            <JsonEditor
              value={outputPayload ?? ''}
              readOnly
              minHeight="260px"
              maxHeight="min(44vh, 460px)"
              aria-label={t('transform.outputPayload')}
            />
            {outputHeaders && Object.keys(outputHeaders).length > 0 && (
              <OutputBlock label={mode === 'dryRun' ? t('transform.dryRunHeaders') : t('transform.outputHeaders')}>
                <dl className="divide-y divide-rail">
                  {Object.entries(outputHeaders).map(([name, value]) => (
                    <div key={name} className="flex gap-3 px-2.5 py-1.5 font-mono text-[11px]">
                      <dt className="shrink-0 text-muted-foreground">{name}</dt>
                      <dd className="min-w-0 flex-1 truncate text-right">{value}</dd>
                    </div>
                  ))}
                </dl>
              </OutputBlock>
            )}
            {mode === 'dryRun' && dryRunResult?.signature && (
              <OutputBlock
                label={t('transform.dryRunSignature')}
                actions={
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => handleCopy(dryRunResult.signature!)}
                    title={t('common.copy')}
                    aria-label={t('common.copy')}
                  >
                    <Shield className="h-3.5 w-3.5" />
                  </Button>
                }
              >
                <pre className="overflow-x-auto px-2.5 py-2 font-mono text-[11px]">{dryRunResult.signature}</pre>
              </OutputBlock>
            )}
          </>
        )}
      </TabPanel>

      <TabPanel value="diff" active={tab}>
        {!hasRun || !outputPayload ? (
          <ResultPlaceholder icon={GitCompare} title={t('transform.emptyTitle')} hint={t('transform.diffHint')} />
        ) : (
          <DiffView
            before={inputPayload}
            after={outputPayload}
            beforeLabel={t('transform.tabInput')}
            afterLabel={t('transform.tabOutput')}
            maxHeight="min(52vh, 520px)"
          />
        )}
      </TabPanel>

      <TabPanel value="console" active={tab} className="space-y-3">
        {errors.length > 0 && (
          <div className="overflow-hidden border border-halt/40">
            <div className="border-b border-halt/30 bg-halt/10 px-2.5 py-2">
              <p className="text-[13px] font-medium text-foreground">
                {errorLine && (
                  <span className="mr-2 rounded bg-halt/15 px-1.5 py-0.5 font-mono text-[10px] font-medium text-halt">
                    {t('transform.atLine', { line: errorLine })}
                  </span>
                )}
                {failureHeadline}
              </p>
              <p className="mt-1 text-[11px] text-muted-foreground">{t('transform.deliveryEffect')}</p>
            </div>
            <div className="px-2.5 py-1.5">
              <span className="mono-label">{t('transform.errorDetail')}</span>
            </div>
            <ul className="divide-y divide-rail border-t border-rail">
              {errors.map((error, index) => (
                <li key={index} className="px-2.5 py-2 font-mono text-[11px] leading-relaxed text-foreground">
                  {error}
                </li>
              ))}
            </ul>
          </div>
        )}

        {consoleLines.length === 0 && errors.length === 0 ? (
          <ResultPlaceholder
            icon={Terminal}
            title={hasRun ? t('transform.consoleEmptyTitle') : t('transform.emptyTitle')}
            hint={t('transform.consoleHint')}
          />
        ) : (
          consoleLines.length > 0 && (
            <div className="overflow-hidden border border-rail">
              <ul className="divide-y divide-rail font-mono text-[11px]">
                {consoleLines.map((line, index) => (
                  <li key={index} className="flex gap-2 px-2.5 py-1.5">
                    <span
                      className={`w-11 shrink-0 select-none uppercase ${
                        line.level === 'error' ? 'text-halt'
                          : line.level === 'warn' ? 'text-retry'
                            : 'text-muted-foreground'
                      }`}
                    >
                      {line.level}
                    </span>
                    <span className="min-w-0 flex-1 whitespace-pre-wrap break-all">{line.message}</span>
                  </li>
                ))}
              </ul>
            </div>
          )
        )}

        {hasRun && errors.length === 0 && !cancelled && (
          <p className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
            <CheckCircle2 className="h-3 w-3 text-ok" aria-hidden="true" />
            {t('transform.consoleClean', { ms: durationMs })}
          </p>
        )}
      </TabPanel>
    </ResultFrame>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        title={t('transform.title')}
        description={t('transform.subtitle')}
      />
      <Workbench input={editorColumn} result={resultColumn} />

      <Dialog open={saveAsOpen} onOpenChange={setSaveAsOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('transform.saveAsTitle')}</DialogTitle>
            <DialogDescription>{t('transform.saveAsDesc')}</DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5">
            <Label htmlFor="transform-save-as-name">{t('transformations.name')}</Label>
            <Input
              id="transform-save-as-name"
              value={saveAsName}
              onChange={(e) => setSaveAsName(e.target.value)}
              placeholder={t('transform.saveAsPlaceholder')}
              autoFocus
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setSaveAsOpen(false)}>{t('common.cancel')}</Button>
            <Button onClick={handleSaveAs} disabled={!saveAsName.trim() || createTransformation.isPending}>
              {t('common.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
