import React, { useState } from 'react';
import { type Node } from '@xyflow/react';
import { X, Loader2, Plus, Key, AlertCircle, Check, Copy, ExternalLink } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { Button } from '../ui/button';
import { endpointsApi } from '../../api/endpoints.api';
import { transformationsApi } from '../../api/transformations.api';
import { subscriptionsApi } from '../../api/subscriptions.api';
import { apiKeysApi } from '../../api/apiKeys.api';
import { queryKeys, useEventTypes } from '../../api/queries';
import { showSuccess, showApiError } from '../../lib/toast';
import { cn } from '../../lib/utils';
import JsonEditor from '../JsonEditor';
import ConditionTreeEditor, { mkGroup } from '../ConditionTreeEditor';
import type { ConditionNode } from '../../api/rules.api';

interface NodeConfigPanelProps {
  node: Node;
  onUpdate: (nodeId: string, data: Record<string, unknown>) => void;
  onClose: () => void;
}

// The global control style in index.css owns the height, border and focus ring;
// these only add what a required-but-empty field needs on top of it.
const inputCls = 'w-full text-sm';
const inputErrCls = 'w-full text-sm !border-halt focus:!border-halt';

export default function NodeConfigPanel({ node, onUpdate, onClose }: NodeConfigPanelProps) {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const d = node.data as Record<string, unknown>;
  const nodeType = node.type || '';

  const updateField = (key: string, value: unknown) => {
    onUpdate(node.id, { ...d, [key]: value });
  };

  const updateJsonField = (key: string, raw: string) => {
    try {
      updateField(key, JSON.parse(raw));
    } catch {
      // store raw string so user can keep typing
    }
  };

  const jsonStr = (val: unknown): string => {
    if (val == null) return '';
    if (typeof val === 'string') return val;
    try { return JSON.stringify(val, null, 2); } catch { return ''; }
  };

  return (
    <div className="flex h-full w-80 flex-col overflow-y-auto border-l border-rail bg-card">
      <div className="flex items-center justify-between border-b border-rail px-4 py-3">
        <h3 className="mono-label">{t('workflows.builder.configureNode')}</h3>
        <Button variant="ghost" size="icon-sm" onClick={onClose} title={t('common.close')} aria-label={t('common.close')}>
          <X className="h-4 w-4" />
        </Button>
      </div>

      <div className="p-4 space-y-4 flex-1">
        {/* Label — all nodes */}
        <Field label={t('workflows.nodeConfig.label')}>
          <input
            value={String(d.label || '')}
            onChange={(e) => updateField('label', e.target.value)}
            className={inputCls}
          />
        </Field>

        {/* ── Trigger node ────────────────────────────────────────── */}
        {nodeType === 'webhookTrigger' && (
          <>
            <Field label={t('workflows.nodeConfig.eventTypePattern')} hint={t('workflows.nodeConfig.eventTypePatternHint')}>
              <input
                value={String(d.eventTypePattern || '')}
                onChange={(e) => updateField('eventTypePattern', e.target.value)}
                placeholder="*"
                className={inputCls}
              />
            </Field>
            <ApiKeyInfo />
          </>
        )}

        {/* ── Filter node ─────────────────────────────────────────── */}
        {nodeType === 'filter' && (
          <Field label={t('workflows.nodeConfig.conditions')} hint={t('workflows.nodeConfig.conditionsHint')}>
            <ConditionTreeEditor
              node={(d.conditions as ConditionNode) || mkGroup('AND')}
              onChange={(updated) => updateField('conditions', updated)}
              onRemove={() => updateField('conditions', mkGroup('AND'))}
              compact
            />
          </Field>
        )}

        {/* ── Transform node ──────────────────────────────────────── */}
        {nodeType === 'transform' && (
          <TransformSource
            transformationId={String(d.transformationId || '')}
            template={String(d.template || '')}
            onUseSaved={(id) => onUpdate(node.id, { ...d, transformationId: id })}
            onUseInline={(value) => onUpdate(node.id, { ...d, transformationId: '', template: value })}
          />
        )}

        {/* ── HTTP node ───────────────────────────────────────────── */}
        {nodeType === 'http' && (
          <>
            <Field label={t('workflows.nodeConfig.method')}>
              <select
                value={String(d.method || 'POST')}
                onChange={(e) => updateField('method', e.target.value)}
                className={inputCls}
              >
                {['GET','POST','PUT','PATCH','DELETE'].map(m => <option key={m} value={m}>{m}</option>)}
              </select>
            </Field>
            <Field label={t('workflows.nodeConfig.url')} required error={!d.url ? t('workflows.validation.required') : undefined}>
              <EndpointQuickFill onSelect={(url) => updateField('url', url)} />
              <input
                value={String(d.url || '')}
                onChange={(e) => updateField('url', e.target.value)}
                placeholder="https://api.example.com/webhook"
                className={d.url ? inputCls : inputErrCls}
              />
            </Field>
            <Field label={t('workflows.nodeConfig.headers')}>
              <JsonEditor
                value={jsonStr(d.headers) || '{}'}
                onChange={(v) => updateJsonField('headers', v)}
                minHeight="60px"
                maxHeight="120px"
              />
            </Field>
            <Field label={t('workflows.nodeConfig.body')} hint={t('workflows.nodeConfig.bodyHint')}>
              <JsonEditor
                value={jsonStr(d.body)}
                onChange={(v) => {
                  if (!v.trim()) { updateField('body', null); return; }
                  updateJsonField('body', v);
                }}
                minHeight="80px"
                maxHeight="200px"
              />
            </Field>
            <Field label={t('workflows.nodeConfig.timeout')}>
              <input
                type="number"
                min={1}
                max={60}
                value={Number(d.timeout || 30)}
                onChange={(e) => updateField('timeout', parseInt(e.target.value) || 30)}
                className={inputCls}
              />
            </Field>
          </>
        )}

        {/* ── Slack node ──────────────────────────────────────────── */}
        {nodeType === 'slack' && (
          <>
            <Field label={t('workflows.nodeConfig.webhookUrl')} required error={!d.webhookUrl ? t('workflows.validation.required') : undefined}>
              <input
                value={String(d.webhookUrl || '')}
                onChange={(e) => updateField('webhookUrl', e.target.value)}
                placeholder="https://hooks.slack.com/services/..."
                className={d.webhookUrl ? inputCls : inputErrCls}
              />
            </Field>
            <Field label={t('workflows.nodeConfig.channel')} hint={t('workflows.nodeConfig.channelHint')}>
              <input
                value={String(d.channel || '')}
                onChange={(e) => updateField('channel', e.target.value)}
                placeholder="#general"
                className={inputCls}
              />
            </Field>
            <Field label={t('workflows.nodeConfig.message')} hint={t('workflows.nodeConfig.messageHint')}>
              <JsonEditor
                value={String(d.message || '')}
                onChange={(v) => updateField('message', v)}
                placeholder="New payment: ${{data.amount}}"
                minHeight="80px"
                maxHeight="200px"
              />
            </Field>
          </>
        )}

        {/* ── Delivery node ───────────────────────────────────────── */}
        {nodeType === 'delivery' && (
          <EndpointSelector
            value={String(d.endpointId || '')}
            onChange={(val) => updateField('endpointId', val)}
          />
        )}

        {/* ── Branch node ─────────────────────────────────────────── */}
        {nodeType === 'branch' && (
          <Field label={t('workflows.nodeConfig.conditions')} hint={t('workflows.nodeConfig.branchHint')}>
            <ConditionTreeEditor
              node={(d.conditions as ConditionNode) || mkGroup('AND')}
              onChange={(updated) => updateField('conditions', updated)}
              onRemove={() => updateField('conditions', mkGroup('AND'))}
              compact
            />
          </Field>
        )}

        {/* ── Delay node ──────────────────────────────────────────── */}
        {nodeType === 'delay' && (
          <Field label={t('workflows.nodeConfig.delaySeconds')} hint={t('workflows.nodeConfig.delaySecondsHint')}>
            <input
              type="number"
              min={1}
              max={300}
              value={Number(d.delaySeconds || 5)}
              onChange={(e) => updateField('delaySeconds', parseInt(e.target.value) || 5)}
              className={inputCls}
            />
          </Field>
        )}

        {/* ── Create Event node ───────────────────────────────────── */}
        {nodeType === 'createEvent' && (
          <>
            <Field label={t('workflows.nodeConfig.eventType')} hint={t('workflows.nodeConfig.eventTypeHint')} required error={!d.eventType ? t('workflows.validation.required') : undefined}>
              {/* Suggestions, not a closed list: the catalogue holds the types somebody has
                  written a schema for, and emitting one that has no schema yet is allowed. */}
              <input
                list="workflow-event-types"
                value={String(d.eventType || '')}
                onChange={(e) => updateField('eventType', e.target.value)}
                placeholder="order.completed"
                className={d.eventType ? inputCls : inputErrCls}
              />
              <EventTypeSuggestions />
            </Field>
            <Field label={t('workflows.nodeConfig.projectId')} hint={t('workflows.nodeConfig.projectIdHint')}>
              <div className="flex gap-1.5">
                <input
                  value={String(d.projectId || '')}
                  onChange={(e) => updateField('projectId', e.target.value)}
                  placeholder={projectId || 'UUID'}
                  className={`${inputCls} flex-1 font-mono text-xs`}
                />
                <Button
                  variant="outline"
                  size="sm"
                  className="shrink-0 text-[10px] px-2"
                  onClick={() => projectId && updateField('projectId', projectId)}
                >
                  {t('workflows.nodeConfig.useCurrentProject')}
                </Button>
              </div>
            </Field>
            <Field label={t('workflows.nodeConfig.payloadTemplate')} hint={t('workflows.nodeConfig.payloadTemplateHint')}>
              <JsonEditor
                value={String(d.payloadTemplate || '')}
                onChange={(v) => updateField('payloadTemplate', v)}
                placeholder='{"customer":"{{data.name}}"}'
                minHeight="100px"
                maxHeight="200px"
              />
            </Field>
            <SubscriptionInfo />
          </>
        )}
      </div>
    </div>
  );
}

// ── Endpoint selector with inline creation ─────────────────────────────

function EndpointSelector({ value, onChange }: { value: string; onChange: (val: string) => void }) {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [newUrl, setNewUrl] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const { data: endpoints, isLoading } = useQuery({
    queryKey: queryKeys.endpoints.list(projectId!),
    queryFn: () => endpointsApi.list(projectId!),
    enabled: !!projectId,
  });

  const createMut = useMutation({
    mutationFn: () => endpointsApi.create(projectId!, { url: newUrl, description: newDesc || undefined, enabled: true }),
    onSuccess: (ep) => {
      showSuccess(t('workflows.nodeConfig.endpointCreated'));
      qc.invalidateQueries({ queryKey: queryKeys.endpoints.list(projectId!) });
      onChange(ep.id);
      setShowCreate(false);
      setNewUrl('');
      setNewDesc('');
    },
    onError: (err) => showApiError(err, t('workflows.nodeConfig.endpointCreateFailed')),
  });

  return (
    <>
      <Field label={t('workflows.nodeConfig.endpointId')} hint={t('workflows.nodeConfig.endpointIdHint')} required error={!value ? t('workflows.validation.required') : undefined}>
        {isLoading ? (
          <div className="flex items-center gap-2 text-xs text-muted-foreground py-1">
            <Loader2 className="h-3 w-3 animate-spin" />
            {t('workflows.nodeConfig.loadingEndpoints')}
          </div>
        ) : (
          <select
            value={value}
            onChange={(e) => onChange(e.target.value)}
            className={value ? inputCls : inputErrCls}
          >
            <option value="">{t('workflows.nodeConfig.selectEndpoint')}</option>
            {endpoints?.map((ep) => (
              <option key={ep.id} value={ep.id}>
                {ep.url}{ep.description ? ` — ${ep.description}` : ''}
              </option>
            ))}
          </select>
        )}
      </Field>

      {!showCreate ? (
        <Button variant="outline" size="sm" className="w-full gap-1.5 text-xs" onClick={() => setShowCreate(true)}>
          <Plus className="h-3 w-3" />
          {t('workflows.nodeConfig.createEndpoint')}
        </Button>
      ) : (
        <div className="space-y-2 rounded-md border border-rail bg-secondary/30 p-3">
          <p className="mono-label">{t('workflows.nodeConfig.newEndpoint')}</p>
          <Field label="URL" required error={showCreate && !newUrl ? t('workflows.validation.required') : undefined}>
            <input
              value={newUrl}
              onChange={(e) => setNewUrl(e.target.value)}
              placeholder="https://api.example.com/webhooks"
              className={newUrl ? inputCls : inputErrCls}
            />
          </Field>
          <Field label={t('workflows.nodeConfig.description')}>
            <input
              value={newDesc}
              onChange={(e) => setNewDesc(e.target.value)}
              placeholder={t('workflows.nodeConfig.descriptionPlaceholder')}
              className={inputCls}
            />
          </Field>
          <div className="flex gap-2 pt-1">
            <Button variant="ghost" size="sm" className="text-xs flex-1" onClick={() => setShowCreate(false)}>
              {t('workflows.cancel')}
            </Button>
            <Button
              size="sm"
              className="text-xs flex-1 gap-1"
              disabled={!newUrl || createMut.isPending}
              onClick={() => createMut.mutate()}
            >
              {createMut.isPending ? <Loader2 className="h-3 w-3 animate-spin" /> : <Check className="h-3 w-3" />}
              {t('workflows.nodeConfig.create')}
            </Button>
          </div>
        </div>
      )}
    </>
  );
}

// ── API Key info + creation for trigger node ────────────────────────────

/**
 * Where a transform node's reshaping comes from: the project's transformation library, or a
 * template written here.
 *
 * <p>The library was unreachable from the canvas. A project could build up named
 * transformations on the Transformations page, point rule actions at them — and then, in a
 * workflow, have to retype one into a box, in a different syntax, with no error to say the
 * syntaxes differ. A saved transformation is `${$.json.path}`; this node's own template is
 * `{{field.path}}`. Text written for one does nothing in the other.
 *
 * <p>So the choice is explicit rather than inferred from what somebody pasted, and picking the
 * library does not mean leaving the canvas to fill it: a transformation created here is saved
 * to the project and selected, the same way the endpoint selector below already works.
 */
function TransformSource({ transformationId, template, onUseSaved, onUseInline }: {
  transformationId: string;
  template: string;
  onUseSaved: (id: string) => void;
  onUseInline: (template: string) => void;
}) {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newTemplate, setNewTemplate] = useState('{}');

  const usingSaved = !!transformationId;

  const { data: transformations, isLoading } = useQuery({
    queryKey: queryKeys.transformations.list(projectId!),
    queryFn: () => transformationsApi.list(projectId!),
    enabled: !!projectId,
  });

  const createMut = useMutation({
    mutationFn: () => transformationsApi.create(projectId!, {
      name: newName,
      template: newTemplate,
      enabled: true,
    }),
    onSuccess: (created) => {
      showSuccess(t('workflows.nodeConfig.transformationCreated'));
      qc.invalidateQueries({ queryKey: queryKeys.transformations.list(projectId!) });
      onUseSaved(created.id);
      setShowCreate(false);
      setNewName('');
      setNewTemplate('{}');
    },
    onError: (err) => showApiError(err, t('workflows.nodeConfig.transformationCreateFailed')),
  });

  return (
    <>
      <Field label={t('workflows.nodeConfig.transformSource')} hint={t('workflows.nodeConfig.transformSourceHint')}>
        <div role="group" aria-label={t('workflows.nodeConfig.transformSource')} className="flex gap-0.5 rounded-lg border border-rail p-0.5">
          <button
            type="button"
            aria-pressed={usingSaved}
            onClick={() => onUseSaved(transformations?.[0]?.id ?? '')}
            className={cn('flex-1 rounded-md px-2 py-1 text-xs transition-colors',
              usingSaved ? 'bg-primary font-medium text-primary-foreground' : 'text-muted-foreground hover:text-foreground')}
          >
            {t('workflows.nodeConfig.transformSourceSaved')}
          </button>
          <button
            type="button"
            aria-pressed={!usingSaved}
            onClick={() => onUseInline(template || '{}')}
            className={cn('flex-1 rounded-md px-2 py-1 text-xs transition-colors',
              !usingSaved ? 'bg-primary font-medium text-primary-foreground' : 'text-muted-foreground hover:text-foreground')}
          >
            {t('workflows.nodeConfig.transformSourceInline')}
          </button>
        </div>
      </Field>

      {usingSaved ? (
        <>
          <Field
            label={t('workflows.nodeConfig.transformation')}
            hint={t('workflows.nodeConfig.transformationHint')}
            required
            error={!transformationId ? t('workflows.validation.required') : undefined}
          >
            {isLoading ? (
              <div className="flex items-center gap-2 py-1 text-xs text-muted-foreground">
                <Loader2 className="h-3 w-3 animate-spin" />
                {t('workflows.nodeConfig.loadingTransformations')}
              </div>
            ) : (
              <select
                value={transformationId}
                onChange={(e) => onUseSaved(e.target.value)}
                className={transformationId ? inputCls : inputErrCls}
                aria-label={t('workflows.nodeConfig.transformation')}
              >
                <option value="">{t('workflows.nodeConfig.selectTransformation')}</option>
                {transformations?.map((tr) => (
                  <option key={tr.id} value={tr.id}>
                    {tr.name}{tr.enabled === false ? ` — ${t('common.disabled')}` : ''}
                  </option>
                ))}
              </select>
            )}
          </Field>

          {!showCreate ? (
            <Button variant="outline" size="sm" className="w-full gap-1.5 text-xs" onClick={() => setShowCreate(true)}>
              <Plus className="h-3 w-3" />
              {t('workflows.nodeConfig.createTransformation')}
            </Button>
          ) : (
            <div className="space-y-2 rounded-md border border-rail bg-secondary/30 p-3">
              <p className="mono-label">{t('workflows.nodeConfig.newTransformation')}</p>
              <Field label={t('workflows.nodeConfig.transformationName')} required error={!newName ? t('workflows.validation.required') : undefined}>
                <input
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder={t('workflows.nodeConfig.transformationNamePlaceholder')}
                  className={newName ? inputCls : inputErrCls}
                />
              </Field>
              <Field label={t('workflows.nodeConfig.template')} hint={t('workflows.nodeConfig.savedTemplateHint')}>
                <JsonEditor
                  value={newTemplate}
                  onChange={setNewTemplate}
                  placeholder={'{"email":"${$.customer.email}"}'}
                  minHeight="100px"
                  maxHeight="200px"
                />
              </Field>
              <div className="flex gap-1.5">
                <Button size="sm" className="flex-1 text-xs" disabled={!newName.trim() || createMut.isPending} onClick={() => createMut.mutate()}>
                  {createMut.isPending && <Loader2 className="h-3 w-3 animate-spin" />}
                  {t('common.create')}
                </Button>
                <Button variant="ghost" size="sm" className="text-xs" onClick={() => setShowCreate(false)}>
                  {t('common.cancel')}
                </Button>
              </div>
            </div>
          )}
        </>
      ) : (
        <Field label={t('workflows.nodeConfig.template')} hint={t('workflows.nodeConfig.templateHint')}>
          <JsonEditor
            value={template || '{}'}
            onChange={onUseInline}
            placeholder='{"name":"{{data.customer}}"}'
            minHeight="120px"
            maxHeight="250px"
          />
        </Field>
      )}
    </>
  );
}

/**
 * The event types this project has a schema for, offered to the create-event node as
 * suggestions. A datalist rather than a select: emitting a type the catalogue has never seen
 * is allowed, and turning that into a validation error would be a new rule this node does not
 * have.
 */
function EventTypeSuggestions() {
  const { projectId } = useParams<{ projectId: string }>();
  const { data: eventTypes } = useEventTypes(projectId);
  return (
    <datalist id="workflow-event-types">
      {eventTypes?.map((type) => <option key={type.id} value={type.name} />)}
    </datalist>
  );
}

function ApiKeyInfo() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [newKeyName, setNewKeyName] = useState('');
  const [createdKey, setCreatedKey] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const { data: keys, isLoading } = useQuery({
    queryKey: ['api-keys', projectId],
    queryFn: () => apiKeysApi.list(projectId!),
    enabled: !!projectId,
  });

  const createMut = useMutation({
    mutationFn: () => apiKeysApi.create(projectId!, { name: newKeyName || 'Workflow Key' }),
    onSuccess: (resp) => {
      showSuccess(t('workflows.nodeConfig.apiKeyCreated'));
      qc.invalidateQueries({ queryKey: ['api-keys', projectId] });
      if (resp.key) {
        setCreatedKey(resp.key);
      }
      setShowCreate(false);
      setNewKeyName('');
    },
    onError: (err) => showApiError(err, t('workflows.nodeConfig.apiKeyCreateFailed')),
  });

  const handleCopy = () => {
    if (createdKey) {
      navigator.clipboard.writeText(createdKey);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const activeKeys = keys?.filter(k => !k.revokedAt) || [];

  return (
    <div className="space-y-2 rounded-md border border-rail bg-secondary/30 p-3">
      <div className="flex items-center gap-1.5">
        <Key className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="mono-label">
          {t('workflows.nodeConfig.apiKeys')}
        </span>
      </div>

      {/* Created key banner — shown once */}
      {createdKey && (
        <div className="space-y-1 rounded-md border border-ok/30 bg-ok-soft p-2">
          <p className="text-[10px] font-medium text-ok">{t('workflows.nodeConfig.apiKeyCopyWarning')}</p>
          <div className="flex items-center gap-1">
            <code className="flex-1 text-[10px] font-mono bg-background rounded px-1.5 py-0.5 truncate select-all">{createdKey}</code>
            <button onClick={handleCopy} className="p-1 rounded hover:bg-muted transition-colors shrink-0" title={t('apiKeys.keyDialog.copyKey')} aria-label={t('apiKeys.keyDialog.copyKey')}>
              {copied ? <Check className="h-3 w-3 text-ok" aria-hidden /> : <Copy className="h-3 w-3 text-muted-foreground" aria-hidden />}
            </button>
          </div>
          <Button variant="ghost" size="sm" className="w-full text-[10px] mt-1" onClick={() => setCreatedKey(null)}>
            {t('workflows.nodeConfig.dismissKey')}
          </Button>
        </div>
      )}

      {/* Key list */}
      {isLoading ? (
        <div className="flex items-center gap-2 text-xs text-muted-foreground"><Loader2 className="h-3 w-3 animate-spin" /> ...</div>
      ) : activeKeys.length === 0 ? (
        <p className="flex items-center gap-1 text-[10px] text-retry">
          <AlertCircle className="h-3 w-3" />
          {t('workflows.nodeConfig.noApiKeys')}
        </p>
      ) : (
        <div className="space-y-1">
          {activeKeys.slice(0, 3).map(k => (
            <div key={k.id} className="flex items-center gap-2 text-[10px]">
              <span className="h-1.5 w-1.5 rounded-full bg-ok" />
              <span className="font-mono text-muted-foreground">{k.keyPrefix}•••</span>
              <span className="truncate">{k.name}</span>
            </div>
          ))}
          {activeKeys.length > 3 && (
            <p className="text-[10px] text-muted-foreground">+{activeKeys.length - 3} {t('workflows.nodeConfig.more')}</p>
          )}
        </div>
      )}

      {/* Inline create */}
      {!showCreate ? (
        <Button variant="outline" size="sm" className="w-full gap-1.5 text-[10px]" onClick={() => setShowCreate(true)}>
          <Plus className="h-3 w-3" />
          {t('workflows.nodeConfig.createApiKey')}
        </Button>
      ) : (
        <div className="space-y-2 border-t border-rail pt-1">
          <Field label={t('workflows.nodeConfig.apiKeyName')}>
            <input
              value={newKeyName}
              onChange={(e) => setNewKeyName(e.target.value)}
              placeholder="Workflow Key"
              className={inputCls}
            />
          </Field>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" className="text-[10px] flex-1" onClick={() => setShowCreate(false)}>
              {t('workflows.cancel')}
            </Button>
            <Button
              size="sm"
              className="text-[10px] flex-1 gap-1"
              disabled={createMut.isPending}
              onClick={() => createMut.mutate()}
            >
              {createMut.isPending ? <Loader2 className="h-3 w-3 animate-spin" /> : <Key className="h-3 w-3" />}
              {t('workflows.nodeConfig.create')}
            </Button>
          </div>
        </div>
      )}

      <p className="text-[10px] text-muted-foreground">{t('workflows.nodeConfig.apiKeysHint')}</p>
    </div>
  );
}

// ── Subscription info for createEvent node ─────────────────────────────

function SubscriptionInfo() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [subEventType, setSubEventType] = useState('*');
  const [subEndpointId, setSubEndpointId] = useState('');

  const { data: subscriptions, isLoading } = useQuery({
    queryKey: queryKeys.subscriptions.list(projectId!),
    queryFn: () => subscriptionsApi.list(projectId!),
    enabled: !!projectId,
  });

  const { data: endpoints } = useQuery({
    queryKey: queryKeys.endpoints.list(projectId!),
    queryFn: () => endpointsApi.list(projectId!),
    enabled: !!projectId,
  });

  const createMut = useMutation({
    mutationFn: () => subscriptionsApi.create(projectId!, {
      endpointId: subEndpointId,
      eventType: subEventType,
      enabled: true,
    }),
    onSuccess: () => {
      showSuccess(t('workflows.nodeConfig.subscriptionCreated'));
      qc.invalidateQueries({ queryKey: queryKeys.subscriptions.list(projectId!) });
      setShowCreate(false);
    },
    onError: (err) => showApiError(err, t('workflows.nodeConfig.subscriptionCreateFailed')),
  });

  const activeSubs = subscriptions?.filter(s => s.enabled) || [];

  return (
    <div className="space-y-2 rounded-md border border-rail bg-secondary/30 p-3">
      <p className="mono-label">
        {t('workflows.nodeConfig.subscriptions')}
      </p>
      <p className="text-[10px] text-muted-foreground">{t('workflows.nodeConfig.subscriptionsHint')}</p>
      {isLoading ? (
        <div className="flex items-center gap-2 text-xs text-muted-foreground"><Loader2 className="h-3 w-3 animate-spin" /> ...</div>
      ) : activeSubs.length === 0 ? (
        <p className="flex items-center gap-1 text-[10px] text-retry">
          <AlertCircle className="h-3 w-3" />
          {t('workflows.nodeConfig.noSubscriptions')}
        </p>
      ) : (
        <div className="space-y-1 max-h-24 overflow-y-auto">
          {activeSubs.slice(0, 5).map(s => (
            <div key={s.id} className="flex items-center gap-2 text-[10px]">
              <span className="h-1.5 w-1.5 rounded-full bg-ok" />
              <span className="font-mono">{s.eventType}</span>
              <span className="text-muted-foreground">→</span>
              <span className="truncate text-muted-foreground">{s.endpointId.substring(0, 8)}…</span>
            </div>
          ))}
          {activeSubs.length > 5 && (
            <p className="text-[10px] text-muted-foreground">+{activeSubs.length - 5} {t('workflows.nodeConfig.more')}</p>
          )}
        </div>
      )}

      {!showCreate ? (
        <Button variant="outline" size="sm" className="w-full gap-1.5 text-[10px]" onClick={() => setShowCreate(true)}>
          <Plus className="h-3 w-3" />
          {t('workflows.nodeConfig.createSubscription')}
        </Button>
      ) : (
        <div className="space-y-2 border-t border-rail pt-1">
          <Field label={t('workflows.nodeConfig.eventType')}>
            <input
              value={subEventType}
              onChange={(e) => setSubEventType(e.target.value)}
              placeholder="order.*"
              className={inputCls}
            />
          </Field>
          <Field label={t('workflows.nodeConfig.endpointId')}>
            <select value={subEndpointId} onChange={(e) => setSubEndpointId(e.target.value)} className={subEndpointId ? inputCls : inputErrCls}>
              <option value="">{t('workflows.nodeConfig.selectEndpoint')}</option>
              {endpoints?.map(ep => (
                <option key={ep.id} value={ep.id}>{ep.url}{ep.description ? ` — ${ep.description}` : ''}</option>
              ))}
            </select>
          </Field>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" className="text-[10px] flex-1" onClick={() => setShowCreate(false)}>
              {t('workflows.cancel')}
            </Button>
            <Button
              size="sm"
              className="text-[10px] flex-1 gap-1"
              disabled={!subEndpointId || !subEventType || createMut.isPending}
              onClick={() => createMut.mutate()}
            >
              {createMut.isPending ? <Loader2 className="h-3 w-3 animate-spin" /> : <Check className="h-3 w-3" />}
              {t('workflows.nodeConfig.create')}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Endpoint quick-fill for HTTP node ────────────────────────────────────

function EndpointQuickFill({ onSelect }: { onSelect: (url: string) => void }) {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const [open, setOpen] = useState(false);

  const { data: endpoints } = useQuery({
    queryKey: queryKeys.endpoints.list(projectId!),
    queryFn: () => endpointsApi.list(projectId!),
    enabled: !!projectId && open,
  });

  return (
    <div className="mb-1">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="text-[10px] text-primary hover:underline flex items-center gap-1"
      >
        <ExternalLink className="h-3 w-3" />
        {t('workflows.nodeConfig.pickFromEndpoints')}
      </button>
      {open && endpoints && endpoints.length > 0 && (
        <div className="mt-1 border rounded-md bg-background max-h-28 overflow-y-auto">
          {endpoints.map(ep => (
            <button
              key={ep.id}
              type="button"
              onClick={() => { onSelect(ep.url); setOpen(false); }}
              className="w-full text-left px-2 py-1.5 text-[10px] hover:bg-muted transition-colors border-b last:border-b-0"
            >
              <span className="font-mono text-primary truncate block">{ep.url}</span>
              {ep.description && <span className="text-muted-foreground truncate block">{ep.description}</span>}
            </button>
          ))}
        </div>
      )}
      {open && endpoints && endpoints.length === 0 && (
        <p className="text-[10px] text-muted-foreground mt-1 italic">{t('workflows.nodeConfig.noEndpointsYet')}</p>
      )}
    </div>
  );
}

// ── Field wrapper with validation ──────────────────────────────────────

/**
 * Attaches the field's id to the one control inside it.
 *
 * <p>The label and the control were siblings with nothing joining them, so every input in this
 * panel — the URL a workflow posts to, the endpoint it delivers through, the delay it waits —
 * was announced as an unlabelled textbox, and clicking a label focused nothing. The id is
 * threaded to the first native control found rather than by wrapping the whole field in the
 * label, because the hint underneath would otherwise be read out as part of the name.
 */
function withFieldId(children: React.ReactNode, id: string): React.ReactNode {
  let claimed = false;
  const visit = (node: React.ReactNode): React.ReactNode => {
    if (claimed || !React.isValidElement(node)) return node;
    if (typeof node.type === 'string' && ['input', 'select', 'textarea'].includes(node.type)) {
      claimed = true;
      const element = node as React.ReactElement<{ id?: string }>;
      return element.props.id ? element : React.cloneElement(element, { id });
    }
    return node;
  };
  return React.Children.map(children, visit);
}

function Field({ label, hint, required, error, children }: {
  label: string; hint?: string; required?: boolean; error?: string; children: React.ReactNode;
}) {
  const id = React.useId();
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="text-xs font-medium text-foreground">
        {label}
        {required && <span className="ml-0.5 text-halt">*</span>}
      </label>
      {hint && <p className="text-[10px] text-muted-foreground">{hint}</p>}
      {withFieldId(children, id)}
      {error && <p role="alert" className="flex items-center gap-1 text-[10px] text-halt"><AlertCircle className="h-3 w-3" aria-hidden />{error}</p>}
    </div>
  );
}
