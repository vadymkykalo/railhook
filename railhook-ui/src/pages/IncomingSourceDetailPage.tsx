import { useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  ArrowDownToLine, CheckCircle2, Copy, Globe, Loader2, Pencil, Play, Plus,
  Trash2, Wand2, XCircle,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showCriticalSuccess } from '../lib/toast';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import AttemptRail from '../components/AttemptRail';
import { ladderTicks } from './ConnectionSetupPage';
import type {
  IncomingDestinationResponse, IncomingDestinationRequest, IncomingAuthType,
} from '../types/api.types';
import { transformApi } from '../api/transform.api';
import {
  useTransformations, useIncomingSource, useIncomingDestinations,
  useCreateIncomingDestination, useUpdateIncomingDestination, useDeleteIncomingDestination,
} from '../api/queries';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Textarea } from '../components/ui/textarea';
import { Label } from '../components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { Select } from '../components/ui/select';
import { Switch } from '../components/ui/switch';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { formatJson, isValidJson } from '../lib/json';
import ConfirmDialog from '../components/ConfirmDialog';

/**
 * One incoming source and the destinations its events are forwarded to.
 *
 * The incoming direction has the same shape as the outgoing one — a thing that
 * receives, and standing statements about where what it receives goes — but
 * never borrows its words: these are Destinations receiving Forwards, not
 * subscriptions receiving deliveries.
 */

const AUTH_TYPES: IncomingAuthType[] = ['NONE', 'BEARER', 'BASIC', 'CUSTOM_HEADER'];

// `key` maps to incomingDestinations.retryPresets.<key>.{label,desc}.
const RETRY_PRESETS: { key: string; delays: string; attempts: string }[] = [
  { key: 'aggressive', delays: '10,30,60,120', attempts: '4' },
  { key: 'standard', delays: '60,300,900,3600', attempts: '5' },
  { key: 'patient', delays: '300,900,3600,21600,86400', attempts: '6' },
  { key: 'none', delays: '', attempts: '1' },
];

export default function IncomingSourceDetailPage() {
  const { t } = useTranslation();
  const { projectId, sourceId } = useParams<{ projectId: string; sourceId: string }>();
  const { canManageIncomingSources } = usePermissions();
  const { data: transformationsList } = useTransformations(projectId!);

  const [destPage, setDestPage] = useState(0);
  const [destPageSize, setDestPageSize] = useState(20);

  const {
    data: source, isLoading: sourceLoading, isError: sourceFailed, error: sourceError,
    refetch: refetchSource,
  } = useIncomingSource(projectId, sourceId);
  const {
    data: destPageInfo, isLoading: destsLoading, isError: destsFailed, error: destsError,
    refetch: refetchDests,
  } = useIncomingDestinations(projectId, sourceId, destPage, destPageSize);

  const createDest = useCreateIncomingDestination(projectId!, sourceId!);
  const updateDest = useUpdateIncomingDestination(projectId!, sourceId!);
  const deleteDest = useDeleteIncomingDestination(projectId!, sourceId!);

  const destinations = destPageInfo?.content ?? [];
  const loading = sourceLoading || destsLoading;
  const failed = sourceFailed || destsFailed;
  const destSaving = createDest.isPending || updateDest.isPending;

  const [showDestDialog, setShowDestDialog] = useState(false);
  const [editDest, setEditDest] = useState<IncomingDestinationResponse | null>(null);
  const [destUrl, setDestUrl] = useState('');
  const [destAuthType, setDestAuthType] = useState<IncomingAuthType>('NONE');
  const [destAuthConfig, setDestAuthConfig] = useState('');
  const [destCustomHeaders, setDestCustomHeaders] = useState('');
  const [destEnabled, setDestEnabled] = useState(true);
  const [destMaxAttempts, setDestMaxAttempts] = useState('5');
  const [destTimeout, setDestTimeout] = useState('30');
  const [destRetryDelays, setDestRetryDelays] = useState('60,300,900,3600');
  const [destPayloadTransform, setDestPayloadTransform] = useState('');
  const [destTransformationId, setDestTransformationId] = useState('');

  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  const [validationOk, setValidationOk] = useState(false);
  const [transformPreview, setTransformPreview] = useState<string | null>(null);
  const [transformPreviewLoading, setTransformPreviewLoading] = useState(false);
  const [transformPreviewErrors, setTransformPreviewErrors] = useState<string[]>([]);

  const [deleteDestId, setDeleteDestId] = useState<string | null>(null);

  const copyIngressUrl = async () => {
    if (!source) return;
    await navigator.clipboard.writeText(source.ingressUrl);
    showSuccess(t('incomingSources.toast.urlCopied'));
  };

  const resetValidation = () => {
    setValidationErrors([]);
    setValidationOk(false);
    setTransformPreview(null);
    setTransformPreviewErrors([]);
  };

  const openCreateDest = () => {
    setEditDest(null);
    setDestUrl('');
    setDestAuthType('NONE');
    setDestAuthConfig('');
    setDestCustomHeaders('');
    setDestEnabled(true);
    setDestMaxAttempts('5');
    setDestTimeout('30');
    setDestRetryDelays('60,300,900,3600');
    setDestPayloadTransform('');
    setDestTransformationId('');
    resetValidation();
    setShowDestDialog(true);
  };

  const openEditDest = (d: IncomingDestinationResponse) => {
    setEditDest(d);
    setDestUrl(d.url);
    setDestAuthType(d.authType);
    setDestAuthConfig('');
    setDestCustomHeaders(d.customHeadersJson || '');
    setDestEnabled(d.enabled);
    setDestMaxAttempts(d.maxAttempts.toString());
    setDestTimeout(d.timeoutSeconds.toString());
    setDestRetryDelays(d.retryDelays || '');
    setDestPayloadTransform(d.payloadTransform || '');
    setDestTransformationId(d.transformationId || '');
    resetValidation();
    setShowDestDialog(true);
  };

  const validateConfig = () => {
    const errors: string[] = [];
    if (!destUrl) errors.push(t('incomingDestinations.validation.urlRequired'));
    try { new URL(destUrl); } catch { errors.push(t('incomingDestinations.validation.invalidUrl')); }
    if (destCustomHeaders && !isValidJson(destCustomHeaders)) errors.push(t('incomingDestinations.validation.headersInvalidJson'));
    if (destPayloadTransform && !isValidJson(destPayloadTransform)) errors.push(t('incomingDestinations.validation.transformInvalidJson'));
    const attempts = parseInt(destMaxAttempts);
    if (isNaN(attempts) || attempts < 1 || attempts > 20) errors.push(t('incomingDestinations.validation.attemptsRange'));
    const timeout = parseInt(destTimeout);
    if (isNaN(timeout) || timeout < 1 || timeout > 120) errors.push(t('incomingDestinations.validation.timeoutRange'));
    if (destRetryDelays) {
      for (const part of destRetryDelays.split(',')) {
        if (isNaN(Number(part.trim())) || Number(part.trim()) < 0) {
          errors.push(t('incomingDestinations.validation.retryDelaysFormat'));
          break;
        }
      }
    }
    setValidationErrors(errors);
    setValidationOk(errors.length === 0);
    return errors.length === 0;
  };

  const runTransformPreview = async () => {
    if (!projectId) return;
    setTransformPreviewLoading(true);
    setTransformPreview(null);
    setTransformPreviewErrors([]);
    try {
      const samplePayload = JSON.stringify(
        { event: 'test.event', data: { id: 1, name: 'sample', nested: { key: 'value' } }, timestamp: new Date().toISOString() },
        null,
        2
      );
      const result = await transformApi.preview(projectId, {
        inputPayload: samplePayload,
        transformExpression: destPayloadTransform || undefined,
        customHeaders: destCustomHeaders || undefined,
      });
      if (result.success) {
        setTransformPreview(result.outputPayload ? formatJson(result.outputPayload) : samplePayload);
      }
      setTransformPreviewErrors(result.errors || []);
    } catch {
      setTransformPreviewErrors([t('incomingDestinations.validation.previewFailed')]);
    } finally {
      setTransformPreviewLoading(false);
    }
  };

  const handleSaveDest = async (e: React.FormEvent) => {
    e.preventDefault();
    const data: IncomingDestinationRequest = {
      url: destUrl,
      authType: destAuthType,
      authConfig: destAuthConfig || undefined,
      customHeadersJson: destCustomHeaders || undefined,
      enabled: destEnabled,
      maxAttempts: parseInt(destMaxAttempts) || 5,
      timeoutSeconds: parseInt(destTimeout) || 30,
      retryDelays: destRetryDelays || undefined,
      payloadTransform: destPayloadTransform || undefined,
      // '' rather than null: an omitted transformationId leaves the current one in place, and
      // clearing the field on this form is a request to detach.
      transformationId: destTransformationId,
    };

    try {
      if (editDest) {
        await updateDest.mutateAsync({ id: editDest.id, data });
        showSuccess(t('incomingDestinations.toast.updated'));
      } else {
        await createDest.mutateAsync(data);
        showSuccess(t('incomingDestinations.toast.created'));
      }
      setShowDestDialog(false);
    } catch (err) {
      showApiError(err, editDest ? 'incomingDestinations.toast.updateFailed' : 'incomingDestinations.toast.createFailed');
    }
  };

  const handleDeleteDest = async () => {
    if (!deleteDestId) return;
    try {
      await deleteDest.mutateAsync(deleteDestId);
      showCriticalSuccess(t('incomingDestinations.toast.deleted'));
      setDeleteDestId(null);
    } catch (err) {
      showApiError(err, 'incomingDestinations.toast.deleteFailed');
    }
  };

  if (loading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={4} height="h-24" />
      </PageSkeleton>
    );
  }

  if (failed || !source) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState
          error={sourceError ?? destsError}
          fallbackKey="incomingSources.toast.loadFailed"
          onRetry={() => { refetchSource(); refetchDests(); }}
        />
      </div>
    );
  }

  const newDestinationButton = (
    <PermissionGate allowed={canManageIncomingSources}>
      <VerificationGate>
        <Button onClick={openCreateDest}>
          <Plus className="h-4 w-4" /> {t('incomingDestinations.create')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={`${source.providerType} · ${source.slug}`}
        title={source.name}
        description={t('incomingSources.detailDescription', 'Webhooks arriving at this URL are verified, then forwarded to every destination below.')}
        actions={newDestinationButton}
      />

      <div className="mb-6 grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-sm font-semibold">
              <ArrowDownToLine className="h-4 w-4" aria-hidden /> {t('incomingSources.ingressUrl')}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex items-center gap-2">
              <code className="min-w-0 flex-1 truncate rounded-md border border-rail bg-secondary/50 px-3 py-2 font-mono text-xs">
                {source.ingressUrl}
              </code>
              <Button
                variant="outline" size="icon-sm" onClick={copyIngressUrl}
                title={t('incomingSources.howToSend.copy')} aria-label={t('incomingSources.howToSend.copy')}
              >
                <Copy className="h-3.5 w-3.5" />
              </Button>
            </div>
            <div>
              <div className="mono-label mb-1.5">{t('incomingSources.howToSend.curlExample')}</div>
              <pre className="overflow-x-auto whitespace-pre-wrap break-all rounded-md border border-rail bg-secondary/40 p-3 font-mono text-[11px] text-muted-foreground">
{`curl -X POST ${source.ingressUrl} \\
  -H "Content-Type: application/json" \\
  -d '{"event": "test", "data": {}}'`}
              </pre>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-sm font-semibold">
              {t('incomingSources.verification')}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="space-y-2.5 text-sm">
              <div className="flex items-center justify-between gap-4">
                <dt className="text-muted-foreground">{t('endpoints.status')}</dt>
                <dd>
                  <StatusBadge
                    kind={source.status === 'ACTIVE' ? 'ok' : 'idle'}
                    label={source.status === 'ACTIVE' ? t('incomingSources.active') : t('incomingSources.disabled')}
                  />
                </dd>
              </div>
              <div className="flex items-center justify-between gap-4">
                <dt className="text-muted-foreground">{t('incomingSources.createDialog.verificationMode')}</dt>
                <dd className="font-mono text-xs">{source.verificationMode}</dd>
              </div>
              <div className="flex items-center justify-between gap-4">
                <dt className="text-muted-foreground">{t('incomingSources.createDialog.hmacSecret')}</dt>
                <dd>
                  <StatusBadge
                    kind={source.hmacSecretConfigured ? 'ok' : 'retry'}
                    label={source.hmacSecretConfigured
                      ? t('incomingSources.hmacConfigured')
                      : t('incomingSources.hmacNotConfigured')}
                  />
                </dd>
              </div>
              {source.hmacHeaderName && (
                <div className="flex items-center justify-between gap-4">
                  <dt className="text-muted-foreground">{t('incomingSources.createDialog.hmacHeaderName')}</dt>
                  <dd className="font-mono text-xs">{source.hmacHeaderName}</dd>
                </div>
              )}
              <div className="flex items-center justify-between gap-4">
                <dt className="text-muted-foreground">{t('incomingSources.rateLimit')}</dt>
                <dd className="font-mono text-xs">
                  {source.rateLimitPerSecond ? `${source.rateLimitPerSecond}/s` : '—'}
                </dd>
              </div>
              <div className="flex items-center justify-between gap-4">
                <dt className="text-muted-foreground">{t('incomingSources.created')}</dt>
                <dd className="font-mono text-xs">{formatDateTime(source.createdAt)}</dd>
              </div>
            </dl>
          </CardContent>
        </Card>
      </div>

      <div className="mb-3">
        <h3 className="text-[15px] font-medium">{t('incomingDestinations.title')}</h3>
        <p className="text-sm text-muted-foreground">{t('incomingDestinations.subtitle')}</p>
      </div>

      {destinations.length === 0 ? (
        <EmptyState
          icon={Globe}
          title={t('incomingDestinations.noDestinations')}
          description={t('incomingDestinations.noDestinationsDesc')}
          action={newDestinationButton}
        />
      ) : (
        <>
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('incomingDestinations.createDialog.url')}</TableHead>
                  <TableHead>{t('incomingDestinations.createDialog.authType')}</TableHead>
                  <TableHead>{t('connections.detailLadder', 'Retry ladder')}</TableHead>
                  <TableHead>{t('endpoints.status')}</TableHead>
                  <TableHead>{t('subscriptions.created')}</TableHead>
                  <TableHead className="w-[90px] text-right">
                    <span className="sr-only">{t('common.actions')}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {destinations.map((dest) => (
                  <TableRow key={dest.id}>
                    <TableCell className="max-w-[280px]">
                      <div className="truncate font-mono text-[13px]" title={dest.url}>{dest.url}</div>
                      {dest.transformationName && (
                        <span className="text-xs text-muted-foreground">{dest.transformationName}</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className="font-mono text-[10px]">{dest.authType}</Badge>
                    </TableCell>
                    <TableCell className="w-[220px]">
                      <AttemptRail
                        attempts={ladderTicks(dest.retryDelays ?? '', dest.maxAttempts)}
                        ariaLabel={t('incomingDestinations.ladderLabel', 'Retry ladder: {{count}} attempts', { count: dest.maxAttempts })}
                      />
                      <span className="font-mono text-[11px] text-muted-foreground">
                        {t('incomingDestinations.ladderSummary', '{{attempts}} attempts · {{timeout}}s timeout', {
                          attempts: dest.maxAttempts,
                          timeout: dest.timeoutSeconds,
                        })}
                      </span>
                    </TableCell>
                    <TableCell>
                      <StatusBadge
                        kind={dest.enabled ? 'ok' : 'idle'}
                        label={dest.enabled ? t('incomingDestinations.enabled') : t('incomingDestinations.disabled')}
                      />
                    </TableCell>
                    <TableCell>
                      <span className="font-mono text-[11px] text-muted-foreground">
                        {formatRelativeTime(dest.createdAt)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-1">
                        {canManageIncomingSources && (
                          <>
                            <Button
                              variant="ghost" size="icon-sm" onClick={() => openEditDest(dest)}
                              title={t('common.edit')} aria-label={t('common.edit')}
                            >
                              <Pencil className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              variant="ghost" size="icon-sm" onClick={() => setDeleteDestId(dest.id)}
                              title={t('common.delete')} aria-label={t('common.delete')}
                              className="text-muted-foreground hover:text-halt"
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </Button>
                          </>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>

          {destPageInfo && (
            <TablePagination
              page={destPage}
              pageSize={destPageSize}
              totalElements={destPageInfo.totalElements}
              totalPages={destPageInfo.totalPages}
              onPageChange={setDestPage}
              onPageSizeChange={setDestPageSize}
            />
          )}
        </>
      )}

      <Dialog open={showDestDialog} onOpenChange={setShowDestDialog}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {editDest ? t('incomingDestinations.editDialog.title') : t('incomingDestinations.createDialog.title')}
            </DialogTitle>
            <DialogDescription>
              {editDest ? t('incomingDestinations.editDialog.description') : t('incomingDestinations.createDialog.description')}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleSaveDest}>
            <div className="max-h-[65vh] space-y-4 overflow-y-auto py-4 pr-1">
              <div className="space-y-2">
                <Label htmlFor="dest-url">{t('incomingDestinations.createDialog.url')}</Label>
                <Input
                  id="dest-url" type="url" className="font-mono text-sm"
                  placeholder={t('incomingDestinations.createDialog.urlPlaceholder')}
                  value={destUrl}
                  onChange={(e) => { setDestUrl(e.target.value); resetValidation(); }}
                  required disabled={destSaving} autoFocus
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="dest-auth">{t('incomingDestinations.createDialog.authType')}</Label>
                <Select
                  id="dest-auth" value={destAuthType}
                  onChange={(e) => setDestAuthType(e.target.value as IncomingAuthType)} disabled={destSaving}
                >
                  {AUTH_TYPES.map((a) => <option key={a} value={a}>{a}</option>)}
                </Select>
              </div>
              {destAuthType !== 'NONE' && (
                <div className="space-y-2">
                  <Label htmlFor="dest-auth-config">{t('incomingDestinations.createDialog.authConfig')}</Label>
                  <Input
                    id="dest-auth-config" className="font-mono text-sm"
                    placeholder={t('incomingDestinations.createDialog.authConfigPlaceholder')}
                    value={destAuthConfig} onChange={(e) => setDestAuthConfig(e.target.value)} disabled={destSaving}
                  />
                  <p className="text-xs text-muted-foreground">{t('incomingDestinations.createDialog.authConfigHint')}</p>
                </div>
              )}

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <Label htmlFor="dest-headers">{t('incomingDestinations.createDialog.customHeaders')}</Label>
                  {destCustomHeaders && (
                    <Button
                      type="button" variant="ghost" size="sm"
                      onClick={() => { setDestCustomHeaders(formatJson(destCustomHeaders)); resetValidation(); }}
                    >
                      <Wand2 className="h-3 w-3" /> {t('incomingDestinations.validation.format')}
                    </Button>
                  )}
                </div>
                <Textarea
                  id="dest-headers"
                  placeholder={'{\n  "X-Custom-Header": "value"\n}'}
                  value={destCustomHeaders}
                  onChange={(e) => { setDestCustomHeaders(e.target.value); resetValidation(); }}
                  disabled={destSaving}
                  className={`min-h-[80px] font-mono text-xs ${destCustomHeaders && !isValidJson(destCustomHeaders) ? 'border-halt' : ''}`}
                  rows={3}
                />
                {destCustomHeaders && !isValidJson(destCustomHeaders) && (
                  <p className="flex items-center gap-1 text-xs text-halt">
                    <XCircle className="h-3 w-3" aria-hidden /> {t('incomingDestinations.validation.invalidJson')}
                  </p>
                )}
              </div>

              <div className="space-y-3">
                <Label>{t('incomingDestinations.createDialog.retryPolicy')}</Label>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {RETRY_PRESETS.map((preset) => {
                    const active = destRetryDelays === preset.delays && destMaxAttempts === preset.attempts;
                    return (
                      <button
                        key={preset.key}
                        type="button"
                        aria-pressed={active}
                        className={`rounded-lg border px-3 py-2 text-left transition-colors ${
                          active ? 'border-primary bg-primary/5' : 'border-rail hover:border-primary/50'
                        }`}
                        onClick={() => {
                          setDestRetryDelays(preset.delays);
                          setDestMaxAttempts(preset.attempts);
                          resetValidation();
                        }}
                      >
                        <span className="text-xs font-medium">{t(`incomingDestinations.retryPresets.${preset.key}.label`)}</span>
                        <p className="mt-0.5 text-[10px] text-muted-foreground">
                          {t(`incomingDestinations.retryPresets.${preset.key}.desc`)}
                        </p>
                      </button>
                    );
                  })}
                </div>
                <div className="grid grid-cols-3 gap-3">
                  <div className="space-y-1">
                    <Label htmlFor="dest-attempts" className="text-[11px]">{t('incomingDestinations.createDialog.maxAttempts')}</Label>
                    <Input
                      id="dest-attempts" type="number" min="1" max="20" className="h-8 font-mono text-xs"
                      value={destMaxAttempts}
                      onChange={(e) => { setDestMaxAttempts(e.target.value); resetValidation(); }}
                      disabled={destSaving}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="dest-timeout" className="text-[11px]">{t('incomingDestinations.createDialog.timeout')}</Label>
                    <Input
                      id="dest-timeout" type="number" min="1" max="120" className="h-8 font-mono text-xs"
                      value={destTimeout}
                      onChange={(e) => { setDestTimeout(e.target.value); resetValidation(); }}
                      disabled={destSaving}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="dest-retry" className="text-[11px]">{t('incomingDestinations.createDialog.retryDelays')}</Label>
                    <Input
                      id="dest-retry" placeholder="60,300,900" className="h-8 font-mono text-xs"
                      value={destRetryDelays}
                      onChange={(e) => { setDestRetryDelays(e.target.value); resetValidation(); }}
                      disabled={destSaving}
                    />
                  </div>
                </div>
                <div className="rounded-lg border border-rail p-3">
                  <AttemptRail
                    attempts={ladderTicks(destRetryDelays, parseInt(destMaxAttempts) || 1)}
                    size="full"
                    ariaLabel={t('incomingDestinations.ladderLabel', 'Retry ladder: {{count}} attempts', { count: parseInt(destMaxAttempts) || 1 })}
                  />
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="dest-transformation">{t('incomingDestinations.createDialog.transformation')}</Label>
                <Select
                  id="dest-transformation"
                  value={destTransformationId}
                  onChange={(e) => { setDestTransformationId(e.target.value); resetValidation(); }}
                  disabled={destSaving}
                >
                  <option value="">{t('incomingDestinations.createDialog.noTransformation')}</option>
                  {(transformationsList ?? []).filter((tr) => tr.enabled).map((tr) => (
                    <option key={tr.id} value={tr.id}>{`${tr.name} (v${tr.version})`}</option>
                  ))}
                </Select>
                <p className="text-xs text-muted-foreground">
                  {destTransformationId
                    ? t('incomingDestinations.createDialog.transformationOverrides')
                    : t('incomingDestinations.createDialog.transformationHint')}
                </p>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <Label htmlFor="dest-transform">{t('incomingDestinations.createDialog.payloadTransform')}</Label>
                  <div className="flex items-center gap-1">
                    {destPayloadTransform && (
                      <Button
                        type="button" variant="ghost" size="sm"
                        onClick={() => { setDestPayloadTransform(formatJson(destPayloadTransform)); resetValidation(); }}
                      >
                        <Wand2 className="h-3 w-3" /> {t('incomingDestinations.validation.format')}
                      </Button>
                    )}
                    <Button
                      type="button" variant="outline" size="sm"
                      onClick={runTransformPreview} disabled={transformPreviewLoading}
                    >
                      {transformPreviewLoading ? <Loader2 className="h-3 w-3 animate-spin" /> : <Play className="h-3 w-3" />}
                      {t('incomingDestinations.validation.testPreview')}
                    </Button>
                  </div>
                </div>
                <Textarea
                  id="dest-transform"
                  placeholder={'{\n  "event_type": "$.event",\n  "payload": "$.data"\n}'}
                  value={destPayloadTransform}
                  onChange={(e) => { setDestPayloadTransform(e.target.value); resetValidation(); setTransformPreview(null); }}
                  disabled={destSaving}
                  className={`min-h-[80px] font-mono text-xs ${destPayloadTransform && !isValidJson(destPayloadTransform) ? 'border-halt' : ''}`}
                  rows={4}
                />
                {destPayloadTransform && !isValidJson(destPayloadTransform) && (
                  <p className="flex items-center gap-1 text-xs text-halt">
                    <XCircle className="h-3 w-3" aria-hidden /> {t('incomingDestinations.validation.invalidJson')}
                  </p>
                )}
                <p className="text-xs text-muted-foreground">{t('incomingDestinations.createDialog.payloadTransformHint')}</p>

                {(transformPreview || transformPreviewErrors.length > 0) && (
                  <div className="overflow-hidden rounded-lg border border-rail">
                    {transformPreview && (
                      <div className="bg-secondary/40 p-3">
                        <div className="mono-label mb-1.5">{t('incomingDestinations.validation.previewOutput')}</div>
                        <pre className="max-h-[120px] overflow-x-auto whitespace-pre-wrap font-mono text-[11px]">
                          {transformPreview}
                        </pre>
                      </div>
                    )}
                    {transformPreviewErrors.length > 0 && (
                      <div className="bg-halt-soft p-3">
                        {transformPreviewErrors.map((err, i) => (
                          <p key={i} className="flex items-center gap-1 text-xs text-halt">
                            <XCircle className="h-3 w-3 flex-shrink-0" aria-hidden /> {err}
                          </p>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>

              <div className="flex items-center justify-between">
                <Label htmlFor="dest-enabled">{t('common.enabled')}</Label>
                <Switch id="dest-enabled" checked={destEnabled} onCheckedChange={setDestEnabled} disabled={destSaving} />
              </div>

              {validationErrors.length > 0 && (
                <div className="space-y-1 rounded-lg border border-halt/30 bg-halt-soft p-3">
                  {validationErrors.map((err, i) => (
                    <p key={i} className="flex items-center gap-1.5 text-xs text-halt">
                      <XCircle className="h-3 w-3 flex-shrink-0" aria-hidden /> {err}
                    </p>
                  ))}
                </div>
              )}
              {validationOk && (
                <div className="rounded-lg border border-ok/30 bg-ok-soft p-3">
                  <p className="flex items-center gap-1.5 text-xs text-ok">
                    <CheckCircle2 className="h-3 w-3" aria-hidden /> {t('incomingDestinations.validation.allValid')}
                  </p>
                </div>
              )}
            </div>
            <DialogFooter className="gap-2 sm:gap-0">
              <Button type="button" variant="ghost" size="sm" onClick={validateConfig} disabled={destSaving}>
                <CheckCircle2 className="h-3.5 w-3.5" /> {t('incomingDestinations.validation.validate')}
              </Button>
              <div className="flex-1" />
              <Button type="button" variant="outline" onClick={() => setShowDestDialog(false)} disabled={destSaving}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" disabled={destSaving}>
                {destSaving && <Loader2 className="h-4 w-4 animate-spin" />}
                {destSaving ? t('common.saving') : t('common.save')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteDestId}
        onOpenChange={(open) => !open && setDeleteDestId(null)}
        title={t('incomingDestinations.deleteDialog.title')}
        description={t('incomingDestinations.deleteDialog.description')}
        onConfirm={handleDeleteDest}
        loading={deleteDest.isPending}
      />
    </div>
  );
}
