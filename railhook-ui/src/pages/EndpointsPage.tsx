import { useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Plus, Webhook, Loader2, Trash2, Power, PowerOff, RefreshCw, Send, ShieldCheck,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { showApiError, showError, showSuccess, showCriticalSuccess } from '../lib/toast';
import { formatDate } from '../lib/date';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge, { EnabledBadge, type StatusKind } from '../components/StatusBadge';
import { endpointsApi, type EndpointTestResponse } from '../api/endpoints.api';
import {
  useProject, useEndpointsPaged, useCreateEndpoint, useDeleteEndpoint, useUpdateEndpoint,
  useRotateSecret, useVerifyEndpoint, useSkipVerification,
} from '../api/queries';
import type { EndpointResponse, SignatureScheme } from '../types/api.types';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { Card } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import MtlsConfigModal from '../components/MtlsConfigModal';
import SignatureSchemePicker from '../components/SignatureSchemePicker';
import { SecretField } from './ConnectionSetupPage';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import ConfirmDialog from '../components/ConfirmDialog';

/**
 * The flat list of Endpoints — one half of a connection, for the times you
 * want to work on endpoints as endpoints: add one without subscribing it to
 * anything yet, retire one, or find the one whose verification never landed.
 * The Connections tab is where the two halves are seen together.
 */

function generateSecret(): string {
  const array = new Uint8Array(32);
  crypto.getRandomValues(array);
  return Array.from(array, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function verificationKind(status: EndpointResponse['verificationStatus']): StatusKind {
  switch (status) {
    case 'VERIFIED':
      return 'ok';
    case 'FAILED':
      return 'halt';
    case 'SKIPPED':
      return 'idle';
    default:
      return 'retry';
  }
}

export default function EndpointsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageEndpoints } = usePermissions();
  const queryClient = useQueryClient();

  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [url, setUrl] = useState('');
  const [description, setDescription] = useState('');
  const [rateLimitPerSecond, setRateLimitPerSecond] = useState<number | undefined>(undefined);
  const [allowedSourceIps, setAllowedSourceIps] = useState('');
  const [signatureScheme, setSignatureScheme] = useState<SignatureScheme>('BOTH');
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [toggleId, setToggleId] = useState<string | null>(null);
  const [rotateId, setRotateId] = useState<string | null>(null);
  const [newSecret, setNewSecret] = useState<string | null>(null);
  const [testId, setTestId] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<EndpointTestResponse | null>(null);
  const [mtlsEndpoint, setMtlsEndpoint] = useState<EndpointResponse | null>(null);
  const [verifyingId, setVerifyingId] = useState<string | null>(null);
  const [skippingId, setSkippingId] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  const {
    data: project, isLoading: projectLoading, isError: projectIsError,
    error: projectError, refetch: refetchProject,
  } = useProject(projectId);
  const {
    data: pageInfo, isLoading: endpointsLoading, isError: endpointsIsError,
    error: endpointsError, refetch: refetchEndpoints,
  } = useEndpointsPaged(projectId, currentPage, pageSize);

  const endpoints = pageInfo?.content ?? [];
  const [localEndpointOverrides, setLocalEndpointOverrides] = useState<Record<string, EndpointResponse>>({});
  const displayEndpoints = endpoints.map((e) => localEndpointOverrides[e.id] ?? e);

  const loading = projectLoading || endpointsLoading;
  const isError = projectIsError || endpointsIsError;
  const retry = () => { refetchProject(); refetchEndpoints(); };

  const createEndpoint = useCreateEndpoint(projectId!);
  const deleteEndpoint = useDeleteEndpoint(projectId!);
  const updateEndpoint = useUpdateEndpoint(projectId!);
  const rotateSecret = useRotateSecret(projectId!);
  const verifyEndpoint = useVerifyEndpoint(projectId!);
  const skipVerification = useSkipVerification(projectId!);
  const creating = createEndpoint.isPending;
  const deleting = deleteEndpoint.isPending;
  const toggling = updateEndpoint.isPending;
  const rotating = rotateSecret.isPending;

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!projectId) return;

    try {
      const secret = generateSecret();
      await createEndpoint.mutateAsync({
        url,
        description,
        enabled: true,
        secret,
        rateLimitPerSecond: rateLimitPerSecond || undefined,
        allowedSourceIps: allowedSourceIps || undefined,
        signatureScheme,
      });
      setShowCreateDialog(false);
      setUrl('');
      setDescription('');
      setRateLimitPerSecond(undefined);
      setAllowedSourceIps('');
      setSignatureScheme('BOTH');
      setNewSecret(secret);
      showSuccess(t('endpoints.toast.created'));
    } catch (err) {
      showApiError(err, 'endpoints.toast.createFailed');
    }
  };

  const handleDelete = async () => {
    if (!deleteId) return;
    try {
      await deleteEndpoint.mutateAsync(deleteId);
      showCriticalSuccess(t('endpoints.toast.deleted'));
      setDeleteId(null);
    } catch (err) {
      showApiError(err, 'endpoints.toast.deleteFailed');
    }
  };

  const handleToggle = async () => {
    const endpoint = displayEndpoints.find((e) => e.id === toggleId);
    if (!endpoint) return;
    try {
      await updateEndpoint.mutateAsync({
        id: endpoint.id,
        data: {
          url: endpoint.url,
          description: endpoint.description,
          enabled: !endpoint.enabled,
          rateLimitPerSecond: endpoint.rateLimitPerSecond,
        },
      });
      showSuccess(endpoint.enabled ? t('endpoints.toast.disabled') : t('endpoints.toast.enabled'));
      setToggleId(null);
    } catch (err) {
      showApiError(err, 'endpoints.toast.toggleFailed');
    }
  };

  const handleRotateSecret = async () => {
    if (!rotateId) return;
    try {
      const response = await rotateSecret.mutateAsync(rotateId);
      setNewSecret(response.secret || null);
      showSuccess(t('endpoints.toast.secretRotated'));
    } catch (err) {
      showApiError(err, 'endpoints.toast.rotateFailed');
      setRotateId(null);
    }
  };

  const closeSecretDialog = () => {
    setRotateId(null);
    setNewSecret(null);
  };

  const handleTest = async (endpointId: string) => {
    if (!projectId) return;
    setTestId(endpointId);
    setTesting(true);
    setTestResult(null);
    try {
      const result = await endpointsApi.test(projectId, endpointId);
      setTestResult(result);
      if (result.success) {
        showSuccess(t('endpoints.toast.testSuccess', { status: result.httpStatusCode, latency: result.latencyMs }));
      } else {
        showError(t('endpoints.toast.testFailed', { message: result.message }));
      }
    } catch (err) {
      showApiError(err, 'endpoints.toast.testError');
      setTestId(null);
    } finally {
      setTesting(false);
    }
  };

  const closeTestDialog = () => {
    setTestId(null);
    setTestResult(null);
  };

  const handleVerify = async (endpointId: string) => {
    setVerifyingId(endpointId);
    try {
      const result = await verifyEndpoint.mutateAsync(endpointId);
      if (result.success) showSuccess(t('endpoints.toast.verified'));
      else showError(t('endpoints.toast.verifyFailed', { message: result.message }));
    } catch (err) {
      showApiError(err, 'endpoints.toast.verifyError');
    } finally {
      setVerifyingId(null);
    }
  };

  const handleSkipVerification = async (endpointId: string) => {
    setSkippingId(endpointId);
    try {
      await skipVerification.mutateAsync({
        id: endpointId,
        reason: t('endpoints.skipReason', 'Skipped from the endpoints list'),
      });
      showSuccess(t('endpoints.toast.skipped'));
    } catch (err) {
      showApiError(err, 'endpoints.toast.skipFailed');
    } finally {
      setSkippingId(null);
    }
  };

  const toggleEndpoint = displayEndpoints.find((e) => e.id === toggleId);

  if (loading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={4} height="h-16" />
      </PageSkeleton>
    );
  }

  const newEndpointButton = (
    <PermissionGate allowed={canManageEndpoints}>
      <VerificationGate>
        <Button onClick={() => setShowCreateDialog(true)}>
          <Plus className="h-4 w-4" /> {t('endpoints.newEndpoint')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={project?.name}
        title={t('endpoints.title')}
        description={t('endpoints.descriptionV2', 'Every URL registered to receive this project’s events, with the secret its signatures are computed from.')}
        actions={!isError && displayEndpoints.length > 0 ? newEndpointButton : undefined}
      />

      {isError ? (
        <ErrorState
          error={projectError ?? endpointsError}
          fallbackKey="endpoints.toast.loadFailed"
          onRetry={retry}
        />
      ) : displayEndpoints.length === 0 ? (
        <EmptyState
          icon={Webhook}
          title={t('endpoints.noEndpoints')}
          description={t('endpoints.noEndpointsDesc')}
          action={newEndpointButton}
          docsLink="/docs#endpoints-api"
        />
      ) : (
        <>
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('endpoints.url')}</TableHead>
                  <TableHead>{t('endpoints.verification')}</TableHead>
                  <TableHead>{t('endpoints.status')}</TableHead>
                  <TableHead>{t('subscriptions.created')}</TableHead>
                  <TableHead className="w-[160px] text-right">
                    <span className="sr-only">{t('common.actions')}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {displayEndpoints.map((endpoint) => (
                  <TableRow key={endpoint.id}>
                    <TableCell className="max-w-[320px]">
                      <div className="truncate font-mono text-[13px]" title={endpoint.url}>{endpoint.url}</div>
                      <div className="flex items-center gap-2">
                        {endpoint.description && (
                          <span className="truncate text-xs text-muted-foreground">{endpoint.description}</span>
                        )}
                        {endpoint.rateLimitPerSecond && (
                          <span className="font-mono text-[11px] text-muted-foreground">
                            {endpoint.rateLimitPerSecond}/s
                          </span>
                        )}
                        {endpoint.mtlsEnabled && (
                          <Badge variant="outline" className="font-mono text-[10px]">mTLS</Badge>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap items-center gap-2">
                        <StatusBadge
                          kind={verificationKind(endpoint.verificationStatus)}
                          label={t(`endpoints.${(endpoint.verificationStatus ?? 'PENDING').toLowerCase()}`)}
                        />
                        {canManageEndpoints
                          && (endpoint.verificationStatus === 'PENDING' || endpoint.verificationStatus === 'FAILED') && (
                          <span className="flex items-center gap-1">
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => handleVerify(endpoint.id)}
                              disabled={verifyingId === endpoint.id}
                            >
                              {verifyingId === endpoint.id && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                              {t('endpoints.verify')}
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => handleSkipVerification(endpoint.id)}
                              disabled={skippingId === endpoint.id}
                            >
                              {skippingId === endpoint.id && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                              {t('endpoints.skip')}
                            </Button>
                          </span>
                        )}
                      </div>
                    </TableCell>
                    <TableCell><EnabledBadge enabled={endpoint.enabled} /></TableCell>
                    <TableCell>
                      <span className="font-mono text-[11px] text-muted-foreground">
                        {formatDate(endpoint.createdAt)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-1">
                        {canManageEndpoints && (
                          <>
                            <Button
                              variant="ghost" size="icon-sm"
                              onClick={() => handleTest(endpoint.id)}
                              disabled={testing && testId === endpoint.id}
                              title={t('endpoints.test')} aria-label={t('endpoints.test')}
                            >
                              {testing && testId === endpoint.id
                                ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
                                : <Send className="h-3.5 w-3.5" />}
                            </Button>
                            <Button
                              variant="ghost" size="icon-sm"
                              onClick={() => setToggleId(endpoint.id)}
                              title={endpoint.enabled ? t('common.disable') : t('common.enable')}
                              aria-label={endpoint.enabled ? t('common.disable') : t('common.enable')}
                            >
                              {endpoint.enabled ? <PowerOff className="h-3.5 w-3.5" /> : <Power className="h-3.5 w-3.5" />}
                            </Button>
                            <Button
                              variant="ghost" size="icon-sm"
                              onClick={() => setRotateId(endpoint.id)}
                              title={t('endpoints.rotateSecret')} aria-label={t('endpoints.rotateSecret')}
                            >
                              <RefreshCw className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              variant="ghost" size="icon-sm"
                              onClick={() => setMtlsEndpoint(endpoint)}
                              title={endpoint.mtlsEnabled ? t('endpoints.configureMtls') : t('endpoints.enableMtls')}
                              aria-label={endpoint.mtlsEnabled ? t('endpoints.configureMtls') : t('endpoints.enableMtls')}
                            >
                              <ShieldCheck className={endpoint.mtlsEnabled ? 'h-3.5 w-3.5 text-primary' : 'h-3.5 w-3.5'} />
                            </Button>
                            <Button
                              variant="ghost" size="icon-sm"
                              onClick={() => setDeleteId(endpoint.id)}
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

          {pageInfo && (
            <TablePagination
              page={currentPage}
              pageSize={pageSize}
              totalElements={pageInfo.totalElements}
              totalPages={pageInfo.totalPages}
              onPageChange={setCurrentPage}
              onPageSizeChange={setPageSize}
            />
          )}
        </>
      )}

      <Dialog open={showCreateDialog} onOpenChange={setShowCreateDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('endpoints.createDialog.title')}</DialogTitle>
            <DialogDescription>{t('endpoints.createDialog.description')}</DialogDescription>
          </DialogHeader>
          <form onSubmit={handleCreate}>
            <div className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="url">{t('endpoints.createDialog.url')}</Label>
                <Input
                  id="url" type="url" className="font-mono text-sm"
                  placeholder={t('endpoints.createDialog.urlPlaceholder')}
                  value={url} onChange={(e) => setUrl(e.target.value)}
                  required disabled={creating} autoFocus
                />
                <p className="text-xs text-muted-foreground">{t('endpoints.createDialog.urlHint')}</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="description">{t('endpoints.createDialog.descriptionLabel')}</Label>
                <Textarea
                  id="description"
                  placeholder={t('endpoints.createDialog.descPlaceholder')}
                  value={description} onChange={(e) => setDescription(e.target.value)}
                  disabled={creating} rows={2}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="rateLimit">{t('endpoints.createDialog.rateLimit')}</Label>
                <Input
                  id="rateLimit" type="number" min="1" max="1000" className="font-mono text-sm"
                  placeholder={t('endpoints.createDialog.rateLimitPlaceholder')}
                  value={rateLimitPerSecond || ''}
                  onChange={(e) => setRateLimitPerSecond(e.target.value ? parseInt(e.target.value) : undefined)}
                  disabled={creating}
                />
                <p className="text-xs text-muted-foreground">{t('endpoints.createDialog.rateLimitHint')}</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="allowedSourceIps">{t('endpoints.createDialog.allowedIps')}</Label>
                <Input
                  id="allowedSourceIps" className="font-mono text-sm"
                  placeholder={t('endpoints.createDialog.allowedIpsPlaceholder')}
                  value={allowedSourceIps} onChange={(e) => setAllowedSourceIps(e.target.value)}
                  disabled={creating}
                />
                <p className="text-xs text-muted-foreground">{t('endpoints.createDialog.allowedIpsHint')}</p>
              </div>
              {/* The same choice the Connection surfaces offer. Without it an endpoint created
                  here took the BOTH default silently, and the person creating it was never
                  shown that the scheme — which decides what their receiver has to verify — was
                  a decision at all. */}
              <SignatureSchemePicker
                value={signatureScheme}
                onChange={setSignatureScheme}
                disabled={creating}
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setShowCreateDialog(false)} disabled={creating}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" disabled={creating}>
                {creating && <Loader2 className="h-4 w-4 animate-spin" />}
                {creating ? t('endpoints.createDialog.submitting') : t('endpoints.createDialog.submit')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteId}
        onOpenChange={(open) => !open && setDeleteId(null)}
        title={t('endpoints.deleteDialog.title')}
        description={t('endpoints.deleteDialog.description')}
        onConfirm={handleDelete}
        loading={deleting}
      />

      <AlertDialog open={!!toggleId && !newSecret} onOpenChange={(open) => !open && setToggleId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {toggleEndpoint?.enabled ? t('endpoints.toggleDialog.disableTitle') : t('endpoints.toggleDialog.enableTitle')}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {toggleEndpoint?.enabled ? t('endpoints.toggleDialog.disableDesc') : t('endpoints.toggleDialog.enableDesc')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={toggling}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleToggle} disabled={toggling}>
              {toggling && <Loader2 className="h-4 w-4 animate-spin" />}
              {toggling ? t('endpoints.toggleDialog.processing') : t('common.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={!!rotateId && !newSecret} onOpenChange={(open) => !open && setRotateId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('endpoints.rotateDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('endpoints.rotateDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={rotating}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleRotateSecret} disabled={rotating}>
              {rotating && <Loader2 className="h-4 w-4 animate-spin" />}
              {rotating ? t('endpoints.rotateDialog.rotating') : t('endpoints.rotateDialog.submit')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* The secret is shown once, masked until asked for. */}
      <Dialog open={!!newSecret} onOpenChange={closeSecretDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('endpoints.secretDialog.title')}</DialogTitle>
            <DialogDescription>{t('endpoints.secretDialog.description')}</DialogDescription>
          </DialogHeader>
          {newSecret && <SecretField secret={newSecret} />}
          <p className="text-sm text-muted-foreground">{t('endpoints.secretDialog.hint')}</p>
          <DialogFooter>
            <Button onClick={closeSecretDialog}>{t('endpoints.secretDialog.done')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!testResult} onOpenChange={closeTestDialog}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t('endpoints.testDialog.title')}</DialogTitle>
            <DialogDescription>{t('endpoints.testDialog.description')}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="flex items-center gap-3 rounded-lg border border-rail p-4">
              <StatusBadge
                kind={testResult?.success ? 'ok' : 'halt'}
                label={testResult?.success ? t('endpoints.testDialog.success') : t('endpoints.testDialog.failed')}
              />
              <span className="font-mono text-xs text-muted-foreground">
                {testResult?.httpStatusCode ? `HTTP ${testResult.httpStatusCode} · ` : ''}
                {testResult?.latencyMs ? `${testResult.latencyMs}ms` : t('endpoints.testDialog.noResponse')}
              </span>
            </div>

            {testResult?.responseBody && (
              <div className="space-y-1.5">
                <div className="mono-label">{t('endpoints.testDialog.responseBody')}</div>
                <pre className="max-h-48 overflow-auto rounded-md border border-rail bg-secondary/40 p-3 font-mono text-xs">
                  {testResult.responseBody}
                </pre>
              </div>
            )}

            {testResult?.errorMessage && (
              <div className="space-y-1.5">
                <div className="mono-label">{t('endpoints.testDialog.errorMessage')}</div>
                <p className="rounded-md border border-halt/30 bg-halt-soft p-3 text-sm text-halt">
                  {testResult.errorMessage}
                </p>
              </div>
            )}

            {testResult?.message && (
              <div className="space-y-1.5">
                <div className="mono-label">{t('endpoints.testDialog.message')}</div>
                <p className="text-sm text-muted-foreground">{testResult.message}</p>
              </div>
            )}
          </div>
          <DialogFooter>
            <Button onClick={closeTestDialog}>{t('common.close')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {mtlsEndpoint && projectId && (
        <MtlsConfigModal
          open={!!mtlsEndpoint}
          onOpenChange={(open) => !open && setMtlsEndpoint(null)}
          projectId={projectId}
          endpoint={mtlsEndpoint}
          onUpdate={(updated) => {
            setLocalEndpointOverrides((prev) => ({ ...prev, [updated.id]: updated }));
            queryClient.invalidateQueries({ queryKey: ['endpoints', projectId] });
            setMtlsEndpoint(null);
          }}
        />
      )}
    </div>
  );
}
