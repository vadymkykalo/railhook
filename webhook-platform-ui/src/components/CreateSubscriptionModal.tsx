import { useState, useEffect, useRef } from 'react';
import { Loader2, FileJson2, CheckCircle2, AlertTriangle, Info, Settings2, Zap, ChevronDown, ChevronRight, HelpCircle, Bell } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { subscriptionsApi, SubscriptionResponse } from '../api/subscriptions.api';
import { useTransformations, useEventTypes, useSchemaVersions } from '../api/queries';
import type { EndpointResponse } from '../types/api.types';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Select } from './ui/select';
import { Badge } from './ui/badge';
import { Switch } from './ui/switch';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog';

interface CreateSubscriptionModalProps {
  projectId: string;
  endpoints: EndpointResponse[];
  subscription?: SubscriptionResponse | null;
  /** Preselects the endpoint when opened from a connection's own row. */
  defaultEndpointId?: string;
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

export default function CreateSubscriptionModal({
  projectId,
  endpoints,
  subscription,
  defaultEndpointId,
  open,
  onClose,
  onSuccess,
}: CreateSubscriptionModalProps) {
  const { t } = useTranslation();
  const [endpointId, setEndpointId] = useState('');
  const [eventType, setEventType] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [orderingEnabled, setOrderingEnabled] = useState(false);
  const [maxAttempts, setMaxAttempts] = useState(7);
  const [timeoutSeconds, setTimeoutSeconds] = useState(30);
  const [retryDelays, setRetryDelays] = useState('60,300,900,3600,21600,86400');
  const [payloadTemplate, setPayloadTemplate] = useState('');
  const [customHeaders, setCustomHeaders] = useState('');
  const [transformationId, setTransformationId] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const { data: transformations = [] } = useTransformations(projectId);
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (subscription) {
      setEndpointId(subscription.endpointId);
      setEventType(subscription.eventType);
      setEnabled(subscription.enabled);
      setOrderingEnabled(subscription.orderingEnabled || false);
      setMaxAttempts(subscription.maxAttempts || 7);
      setTimeoutSeconds(subscription.timeoutSeconds || 30);
      setRetryDelays(subscription.retryDelays || '60,300,900,3600,21600,86400');
      setPayloadTemplate(subscription.payloadTemplate || '');
      setCustomHeaders(subscription.customHeaders || '');
      setTransformationId(subscription.transformationId || '');
    } else {
      setEndpointId(defaultEndpointId ?? '');
      setEventType('');
      setEnabled(true);
      setOrderingEnabled(false);
      setMaxAttempts(7);
      setTimeoutSeconds(30);
      setRetryDelays('60,300,900,3600,21600,86400');
      setPayloadTemplate('');
      setCustomHeaders('');
      setTransformationId('');
    }
    setErrors({});
    setShowAdvanced(false);
  }, [subscription, defaultEndpointId, open]);

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!endpointId) {
      newErrors.endpointId = t('createSubscription.validation.endpointRequired');
    }
    if (!eventType.trim()) {
      newErrors.eventType = t('createSubscription.validation.eventTypeRequired');
    } else if (!/^(\*{1,2}|[a-z][a-z0-9_]*)(\.(\*{1,2}|[a-z][a-z0-9_]*))*$/.test(eventType)) {
      newErrors.eventType = t('createSubscription.validation.eventTypeFormat');
    }
    if (maxAttempts < 1 || maxAttempts > 20) {
      newErrors.maxAttempts = t('createSubscription.validation.maxAttempts');
    }
    if (timeoutSeconds < 1 || timeoutSeconds > 60) {
      newErrors.timeoutSeconds = t('createSubscription.validation.timeout');
    }
    if (retryDelays.trim() && !/^\d+(,\d+)*$/.test(retryDelays.trim())) {
      newErrors.retryDelays = t('createSubscription.validation.retryDelays');
    }
    if (payloadTemplate.trim()) {
      try { JSON.parse(payloadTemplate); } catch {
        newErrors.payloadTemplate = t('createSubscription.validation.invalidJson');
      }
    }
    if (customHeaders.trim()) {
      try {
        const parsed = JSON.parse(customHeaders);
        if (typeof parsed !== 'object' || Array.isArray(parsed)) {
          newErrors.customHeaders = t('createSubscription.validation.headersObject');
        }
      } catch {
        newErrors.customHeaders = t('createSubscription.validation.invalidJson');
      }
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const selectedEndpoint = endpoints.find(e => e.id === endpointId);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validate()) {
      return;
    }

    setSaving(true);
    try {
      const payload = {
        endpointId,
        eventType: eventType.trim(),
        enabled,
        orderingEnabled,
        maxAttempts,
        timeoutSeconds,
        retryDelays,
        payloadTemplate: payloadTemplate || undefined,
        customHeaders: customHeaders || undefined,
        transformationId: transformationId || null,
      };

      if (subscription) {
        await subscriptionsApi.update(projectId, subscription.id, payload);
        showSuccess(t('createSubscription.toast.updated'));
      } else {
        await subscriptionsApi.create(projectId, payload);
        showSuccess(t('createSubscription.toast.created'));
      }

      onClose();
      onSuccess();
    } catch (err: any) {
      showApiError(err, 'toast.errors.server');
    } finally {
      setSaving(false);
    }
  };

  const formatRetryDelaysHuman = (delays: string): string => {
    if (!delays.trim()) return '';
    return delays.split(',').map(s => {
      const sec = parseInt(s.trim());
      if (isNaN(sec)) return s.trim();
      if (sec < 60) return `${sec}s`;
      if (sec < 3600) return `${Math.round(sec / 60)}m`;
      return `${Math.round(sec / 3600)}h`;
    }).join(' → ');
  };

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Bell className="h-5 w-5 text-primary" />
            {subscription ? t('createSubscription.titleEdit') : t('createSubscription.titleCreate')}
          </DialogTitle>
          <DialogDescription>
            {subscription
              ? t('createSubscription.descriptionEdit')
              : t('createSubscription.descriptionCreate')}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit}>
          <div className="space-y-5 py-4">
            {/* ── Section 1: Essential ── */}
            <div className="space-y-4">
              <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <Zap className="h-4 w-4 text-primary" />
                {t('createSubscription.sections.essentials')}
              </div>

              {/* Endpoint */}
              <div className="space-y-2">
                <Label htmlFor="endpoint">
                  {t('createSubscription.fields.endpoint')} <span className="text-halt">*</span>
                </Label>
                <Select
                  id="endpoint"
                  value={endpointId}
                  onChange={(e) => setEndpointId(e.target.value)}
                  disabled={saving}
                  required
                >
                  <option value="">{t('createSubscription.fields.selectEndpoint')}</option>
                  {endpoints.map(endpoint => (
                    <option key={endpoint.id} value={endpoint.id}>
                      {endpoint.url}
                    </option>
                  ))}
                </Select>
                {errors.endpointId && (
                  <p className="text-sm text-halt">{errors.endpointId}</p>
                )}
                {endpoints.length === 0 ? (
                  <p className="flex items-center gap-1 text-sm text-retry">
                    <AlertTriangle className="h-3.5 w-3.5" />
                    {t('createSubscription.fields.noEndpoints')}
                  </p>
                ) : (
                  <p className="text-xs text-muted-foreground">
                    {t('createSubscription.fields.endpointHint')}
                    {selectedEndpoint && (
                      <span className="ml-1">{t('createSubscription.fields.selected')}: <code className="bg-muted px-1 rounded text-[11px]">{selectedEndpoint.url}</code></span>
                    )}
                  </p>
                )}
              </div>

              {/* Event Type */}
              <EventTypeField
                projectId={projectId}
                eventType={eventType}
                onChange={setEventType}
                disabled={saving}
                error={errors.eventType}
              />
            </div>

            {/* ── Section 2: Behavior ── */}
            <div className="space-y-3 border-t border-rail pt-4">
              <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <Settings2 className="h-4 w-4 text-muted-foreground" />
                {t('createSubscription.sections.behavior')}
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="flex items-center gap-3 p-3 bg-muted/40 rounded-lg border">
                  <Switch
                    id="enabled"
                    checked={enabled}
                    onCheckedChange={setEnabled}
                    disabled={saving}
                  />
                  <div>
                    <Label htmlFor="enabled" className="cursor-pointer text-sm">
                      {t('createSubscription.fields.enabled')}
                    </Label>
                    <p className="text-[11px] text-muted-foreground">
                      {enabled
                        ? t('createSubscription.fields.enabledHint')
                        : t('createSubscription.fields.disabledHint')}
                    </p>
                  </div>
                </div>

                <div className="flex items-center gap-3 p-3 bg-muted/40 rounded-lg border">
                  <Switch
                    id="orderingEnabled"
                    checked={orderingEnabled}
                    onCheckedChange={setOrderingEnabled}
                    disabled={saving}
                  />
                  <div>
                    <Label htmlFor="orderingEnabled" className="cursor-pointer text-sm">
                      {t('createSubscription.fields.fifoOrdering')}
                    </Label>
                    <p className="text-[11px] text-muted-foreground">
                      {orderingEnabled
                        ? t('createSubscription.fields.fifoOnHint')
                        : t('createSubscription.fields.fifoOffHint')}
                    </p>
                  </div>
                </div>
              </div>
            </div>

            {/* ── Section 3: Advanced ── */}
            <div className="border-t border-rail pt-4">
              <button
                type="button"
                className="flex items-center gap-2 text-sm font-semibold text-foreground hover:text-primary transition-colors w-full"
                onClick={() => setShowAdvanced(!showAdvanced)}
              >
                {showAdvanced ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                <Settings2 className="h-4 w-4 text-muted-foreground" />
                {t('createSubscription.sections.advanced')}
                <span className="text-xs font-normal text-muted-foreground ml-1">— {t('createSubscription.sections.advancedHint')}</span>
              </button>

              {showAdvanced && (
                <div className="space-y-4 mt-4">
                  {/* Retry row */}
                  <div className="grid grid-cols-3 gap-3">
                    <div className="space-y-1.5">
                      <Label htmlFor="maxAttempts" className="text-xs">{t('createSubscription.fields.maxAttempts')}</Label>
                      <Input
                        id="maxAttempts"
                        type="number"
                        min={1}
                        max={20}
                        value={maxAttempts}
                        onChange={(e) => setMaxAttempts(parseInt(e.target.value) || 7)}
                        disabled={saving}
                      />
                      {errors.maxAttempts ? (
                        <p className="text-[11px] text-halt">{errors.maxAttempts}</p>
                      ) : (
                        <p className="text-[11px] text-muted-foreground">{t('createSubscription.fields.maxAttemptsHint')}</p>
                      )}
                    </div>
                    <div className="space-y-1.5">
                      <Label htmlFor="timeoutSeconds" className="text-xs">{t('createSubscription.fields.timeout')}</Label>
                      <Input
                        id="timeoutSeconds"
                        type="number"
                        min={1}
                        max={60}
                        value={timeoutSeconds}
                        onChange={(e) => setTimeoutSeconds(parseInt(e.target.value) || 30)}
                        disabled={saving}
                      />
                      {errors.timeoutSeconds ? (
                        <p className="text-[11px] text-halt">{errors.timeoutSeconds}</p>
                      ) : (
                        <p className="text-[11px] text-muted-foreground">{t('createSubscription.fields.timeoutHint')}</p>
                      )}
                    </div>
                    <div className="space-y-1.5">
                      <Label htmlFor="retryDelays" className="text-xs">{t('createSubscription.fields.retryDelays')}</Label>
                      <Input
                        id="retryDelays"
                        placeholder="60,300,900,3600"
                        value={retryDelays}
                        onChange={(e) => setRetryDelays(e.target.value)}
                        disabled={saving}
                      />
                      {errors.retryDelays ? (
                        <p className="text-[11px] text-halt">{errors.retryDelays}</p>
                      ) : (
                        <p className="text-[11px] text-muted-foreground">
                          {retryDelays.trim() ? formatRetryDelaysHuman(retryDelays) : t('createSubscription.fields.retryDelaysHint')}
                        </p>
                      )}
                    </div>
                  </div>

                  {/* Transformation */}
                  <div className="space-y-1.5">
                    <Label htmlFor="transformationId" className="text-xs flex items-center gap-1.5">
                      {t('createSubscription.fields.transformation')}
                      <span className="text-muted-foreground font-normal">({t('common.optional')})</span>
                    </Label>
                    <Select
                      id="transformationId"
                      value={transformationId}
                      onChange={(e) => setTransformationId(e.target.value)}
                      disabled={saving}
                    >
                      <option value="">{t('createSubscription.fields.noTransformation')}</option>
                      {transformations.filter(tr => tr.enabled).map(tr => (
                        <option key={tr.id} value={tr.id}>{tr.name} (v{tr.version})</option>
                      ))}
                    </Select>
                    {transformationId && (() => {
                      const selected = transformations.find(tr => tr.id === transformationId);
                      return selected ? (
                        <div className="rounded-md border bg-muted/30 p-2.5 text-xs space-y-1">
                          <div className="flex items-center gap-2">
                            <span className="font-medium">{selected.name}</span>
                            <span className="text-muted-foreground">v{selected.version}</span>
                          </div>
                          {selected.description && <p className="text-muted-foreground">{selected.description}</p>}
                        </div>
                      ) : null;
                    })()}
                  </div>

                  {/* Payload template + custom headers side by side */}
                  <div className="grid grid-cols-2 gap-3">
                    <div className="space-y-1.5">
                      <Label htmlFor="payloadTemplate" className="text-xs flex items-center gap-1.5">
                        {t('createSubscription.fields.payloadTemplate')}
                        <span className="text-muted-foreground font-normal">({t('common.optional')})</span>
                      </Label>
                      <textarea
                        id="payloadTemplate"
                        className="w-full h-28 p-2 text-xs font-mono border rounded-md bg-background resize-y"
                        placeholder={'{\n  "id": "${$.id}",\n  "data": "${$.data}"\n}'}
                        value={payloadTemplate}
                        onChange={(e) => setPayloadTemplate(e.target.value)}
                        disabled={saving}
                      />
                      {errors.payloadTemplate ? (
                        <p className="text-[11px] text-halt">{errors.payloadTemplate}</p>
                      ) : (
                        <p className="text-[11px] text-muted-foreground">
                          {t('createSubscription.fields.payloadTemplateHint')}
                        </p>
                      )}
                    </div>
                    <div className="space-y-1.5">
                      <Label htmlFor="customHeaders" className="text-xs flex items-center gap-1.5">
                        {t('createSubscription.fields.customHeaders')}
                        <span className="text-muted-foreground font-normal">({t('common.optional')})</span>
                      </Label>
                      <textarea
                        id="customHeaders"
                        className="w-full h-28 p-2 text-xs font-mono border rounded-md bg-background resize-y"
                        placeholder={'{\n  "X-Api-Key": "key",\n  "Authorization": "Bearer ..."\n}'}
                        value={customHeaders}
                        onChange={(e) => setCustomHeaders(e.target.value)}
                        disabled={saving}
                      />
                      {errors.customHeaders ? (
                        <p className="text-[11px] text-halt">{errors.customHeaders}</p>
                      ) : (
                        <p className="text-[11px] text-muted-foreground">
                          {t('createSubscription.fields.customHeadersHint')}
                        </p>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* ── Summary / Tip ── */}
            {!subscription && endpointId && eventType.trim() && (
              <div className="bg-primary/5 border border-primary/20 rounded-lg p-3 flex items-start gap-2.5">
                <HelpCircle className="h-4 w-4 text-primary mt-0.5 shrink-0" />
                <div className="text-xs space-y-1">
                  <p className="font-medium text-foreground">{t('createSubscription.summary.title')}</p>
                  <p className="text-muted-foreground">
                    {t('createSubscription.summary.description', { eventType, endpoint: selectedEndpoint?.url || t('createSubscription.summary.theEndpoint') })}
                  </p>
                </div>
              </div>
            )}
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={onClose}
              disabled={saving}
            >
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={saving || endpoints.length === 0}>
              {saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              {saving ? t('common.saving') : (subscription ? t('createSubscription.submitEdit') : t('createSubscription.submitCreate'))}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ── Schema-Aware Event Type Field ──────────────────────────────────

function EventTypeField({
  projectId,
  eventType,
  onChange,
  disabled,
  error,
}: {
  projectId: string;
  eventType: string;
  onChange: (v: string) => void;
  disabled: boolean;
  error?: string;
}) {
  const { t } = useTranslation();
  const { data: catalogTypes = [] } = useEventTypes(projectId);
  const [focused, setFocused] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  // Filter catalog by typed value
  const query = eventType.toLowerCase();
  const suggestions = catalogTypes.filter(
    (et) => et.name.toLowerCase().includes(query) || !query
  );

  // Find exact match for schema hint
  const exactMatch = catalogTypes.find(
    (et) => et.name === eventType.trim()
  );

  const showDropdown = focused && suggestions.length > 0 && !exactMatch;

  // Close dropdown on outside click
  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setFocused(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, []);

  return (
    <div className="space-y-2" ref={wrapperRef}>
      <Label htmlFor="eventType">
        {t('createSubscription.fields.eventType')} <span className="text-halt">*</span>
      </Label>
      <div className="relative">
        <Input
          id="eventType"
          placeholder={t('createSubscription.fields.eventTypePlaceholder')}
          value={eventType}
          onChange={(e) => onChange(e.target.value)}
          onFocus={() => setFocused(true)}
          disabled={disabled}
          required
          autoComplete="off"
        />

        {/* Autocomplete dropdown */}
        {showDropdown && (
          <div className="absolute z-50 w-full mt-1 max-h-48 overflow-y-auto rounded-lg border bg-popover shadow-lg">
            {suggestions.map((et) => (
              <button
                key={et.id}
                type="button"
                className="w-full text-left px-3 py-2 text-sm hover:bg-accent transition-colors border-b last:border-b-0"
                onMouseDown={(e) => {
                  e.preventDefault();
                  onChange(et.name);
                  setFocused(false);
                }}
              >
                <div className="flex items-center justify-between">
                  <span className="font-mono text-xs font-medium">{et.name}</span>
                  <div className="flex items-center gap-1.5">
                    {et.latestVersion != null && (
                      <span className="text-[10px] text-muted-foreground">v{et.latestVersion}</span>
                    )}
                    {et.activeVersionStatus === 'ACTIVE' && (
                      <CheckCircle2 className="h-3 w-3 text-ok" />
                    )}
                    {et.hasBreakingChanges && (
                      <AlertTriangle className="h-3 w-3 text-retry" />
                    )}
                  </div>
                </div>
                {et.description && (
                  <p className="text-[11px] text-muted-foreground mt-0.5 line-clamp-1">{et.description}</p>
                )}
              </button>
            ))}
          </div>
        )}
      </div>

      {error && <p className="text-sm text-halt">{error}</p>}

      {/* Schema info badge for exact match */}
      {exactMatch && (
        <div className="rounded-lg border bg-primary/5 overflow-hidden">
          <div className="flex items-start gap-2 p-2.5">
            <FileJson2 className="h-3.5 w-3.5 text-primary mt-0.5 shrink-0" />
            <div className="text-xs space-y-0.5 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="font-mono font-semibold">{exactMatch.name}</span>
                {exactMatch.latestVersion != null && (
                  <span className="text-muted-foreground">v{exactMatch.latestVersion}</span>
                )}
                {exactMatch.activeVersionStatus === 'ACTIVE' && (
                  <Badge variant="ok" className="text-[10px]">
                    <CheckCircle2 className="h-2.5 w-2.5" /> {t('common.active', 'Active')}
                  </Badge>
                )}
                {exactMatch.hasBreakingChanges && (
                  <Badge variant="retry" className="text-[10px]">
                    <AlertTriangle className="h-2.5 w-2.5" /> {t('createSubscription.fields.breaking', 'Breaking')}
                  </Badge>
                )}
              </div>
              {exactMatch.description && (
                <p className="text-muted-foreground">{exactMatch.description}</p>
              )}
            </div>
          </div>
          <SchemaFieldsPreview projectId={projectId} eventTypeId={exactMatch.id} />
        </div>
      )}

      {/* Hint when no match and catalogTypes exist */}
      {!exactMatch && eventType.trim() && catalogTypes.length > 0 && !focused && (
        <p className="text-xs text-muted-foreground flex items-center gap-1">
          <Info className="h-3 w-3" />
          {t('createSubscription.fields.noSchema')}
        </p>
      )}

      <p className="text-xs text-muted-foreground">
        {t('createSubscription.fields.eventTypeHint')}
      </p>
    </div>
  );
}

// ── Schema Fields Preview ──────────────────────────────────────────

interface SchemaField {
  name: string;
  type: string;
  required: boolean;
}

function parseSchemaFields(schemaJson: string): SchemaField[] {
  try {
    const schema = JSON.parse(schemaJson);
    const props = schema.properties || {};
    const required = new Set<string>(schema.required || []);
    return Object.entries(props).map(([name, def]: [string, any]) => ({
      name,
      type: Array.isArray(def?.type) ? def.type.join(' | ') : (def?.type || 'any'),
      required: required.has(name),
    }));
  } catch {
    return [];
  }
}

function SchemaFieldsPreview({ projectId, eventTypeId }: { projectId: string; eventTypeId: string }) {
  const { t } = useTranslation();
  const { data: versions, isLoading } = useSchemaVersions(projectId, eventTypeId);

  // Find the latest active version, or just the latest
  const latest = versions
    ?.slice()
    .sort((a, b) => b.version - a.version)
    .find(v => v.status === 'ACTIVE')
    || versions?.[0];

  if (isLoading) {
    return (
      <div className="px-2.5 pb-2 flex items-center gap-1.5 text-[10px] text-muted-foreground">
        <Loader2 className="h-2.5 w-2.5 animate-spin" /> {t('common.loading')}
      </div>
    );
  }

  if (!latest) return null;

  const fields = parseSchemaFields(latest.schemaJson);
  if (fields.length === 0) return null;

  return (
    <div className="border-t border-primary/10 px-2.5 py-2 space-y-1">
      <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider">
        {t('createSubscription.fields.payloadFields', { version: latest.version })}
      </p>
      <div className="flex flex-wrap gap-1">
        {fields.slice(0, 12).map((f) => (
          <span
            key={f.name}
            className="inline-flex items-center gap-1 text-[10px] font-mono bg-muted/60 px-1.5 py-0.5 rounded"
          >
            <span className="font-medium">{f.name}</span>
            <span className="text-muted-foreground">{f.type}</span>
            {f.required && <span className="text-halt">*</span>}
          </span>
        ))}
        {fields.length > 12 && (
          <span className="text-[10px] text-muted-foreground self-center">
            +{fields.length - 12} {t('common.more', 'more')}
          </span>
        )}
      </div>
    </div>
  );
}
