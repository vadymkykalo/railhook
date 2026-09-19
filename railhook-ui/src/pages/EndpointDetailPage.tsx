import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import {
  ArrowRight, Loader2, Plus, Power, PowerOff, RefreshCw, Send, ShieldCheck, Trash2, Truck,
} from 'lucide-react';
import { showApiError, showCriticalSuccess, showError, showSuccess } from '../lib/toast';
import { formatDateTime } from '../lib/date';
import { endpointsApi, type EndpointTestResponse } from '../api/endpoints.api';
import type { SubscriptionResponse } from '../api/subscriptions.api';
import {
  queryKeys, useConsumerNames, useDeleteEndpoint, useDeliveries, useEndpoint, useEndpoints,
  usePatchSubscription, useProject, useRotateSecret, useSkipVerification, useSubscriptions,
  useUpdateEndpoint, useVerifyEndpoint,
} from '../api/queries';
import type { EndpointResponse, SignatureScheme } from '../types/api.types';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge, { EnabledBadge, kindOfDeliveryStatus, type StatusKind } from '../components/StatusBadge';
import DetailBreadcrumb from '../components/DetailBreadcrumb';
import AttemptRail from '../components/AttemptRail';
import CreateSubscriptionModal from '../components/CreateSubscriptionModal';
import MtlsConfigModal from '../components/MtlsConfigModal';
import SecretField from '../components/SecretField';
import IntegrationSnippet from '../components/IntegrationSnippet';
import SignatureSchemePicker, { sendsStandardHeaders } from '../components/SignatureSchemePicker';
import ConfirmDialog from '../components/ConfirmDialog';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import DeliveryDetailsSheet from './DeliveryDetailsSheet';
import { TimeCell } from './tableParts';
import { ladderTicks } from './ConnectionSetupPage';
import { verifySignatureSnippets } from '../lib/integrationSnippets';
import { usePermissions } from '../auth/usePermissions';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Switch } from '../components/ui/switch';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';

/**
 * One endpoint: where it is, whether it is receiving, what it is subscribed to, how its
 * signatures are checked, and what it was sent lately — with every action on it named.
 *
 * <p>Modelled on the source page, the incoming direction's counterpart. Before it, an endpoint was
 * a row in two lists: Connections folded its settings into an expandable row, and Endpoints put
 * its actions behind five unlabelled icons. Both lists now open this page.
 */

/** How many deliveries the page shows before handing over to the Deliveries list. */
const RECENT = 10;

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

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-right">{children}</dd>
    </div>
  );
}

export default function EndpointDetailPage() {
  const { t } = useTranslation();
  const { projectId, endpointId } = useParams<{ projectId: string; endpointId: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { canManageEndpoints, canManageSubscriptions } = usePermissions();

  const { data: project } = useProject(projectId);
  const {
    data: endpoint, isLoading, isError, error, refetch,
  } = useEndpoint(projectId, endpointId);
  // The subscription dialog picks among the project's endpoints, even opened for this one.
  const { data: endpoints = [] } = useEndpoints(projectId);
  const { data: allSubscriptions = [] } = useSubscriptions(projectId);
  const { data: consumerNames } = useConsumerNames(projectId, !!endpoint?.consumerId);
  const {
    data: deliveryPage, isLoading: deliveriesLoading, refetch: refetchDeliveries,
  } = useDeliveries(projectId, { endpointId, size: RECENT, sort: 'createdAt,desc' });

  const updateEndpoint = useUpdateEndpoint(projectId!);
  const deleteEndpoint = useDeleteEndpoint(projectId!);
  const rotateSecret = useRotateSecret(projectId!);
  const verifyEndpoint = useVerifyEndpoint(projectId!);
  const skipVerification = useSkipVerification(projectId!);
  const patchSubscription = usePatchSubscription(projectId!);

  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<EndpointTestResponse | null>(null);
  const [confirmToggle, setConfirmToggle] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [confirmRotate, setConfirmRotate] = useState(false);
  const [rotated, setRotated] = useState<EndpointResponse | null>(null);
  const [showMtls, setShowMtls] = useState(false);
  // The scheme just chosen, held until the endpoint query catches up, so the radio does not
  // spring back to the old option for the length of a refetch.
  const [pendingScheme, setPendingScheme] = useState<SignatureScheme | null>(null);
  const [subscriptionDialog, setSubscriptionDialog] = useState<{ editing: SubscriptionResponse | null } | null>(null);
  const [openDeliveryId, setOpenDeliveryId] = useState<string | null>(null);

  if (isLoading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={4} height="h-24" />
      </PageSkeleton>
    );
  }

  if (isError || !endpoint) {
    return (
      <div className="p-4 lg:p-6">
        <DetailBreadcrumb projectId={projectId} current={endpointId ?? ''} />
        <ErrorState error={error} fallbackKey="endpoints.toast.loadFailed" onRetry={() => refetch()} />
      </div>
    );
  }

  const subscriptions = allSubscriptions.filter((s) => s.endpointId === endpoint.id);
  const ladderSource = subscriptions[0];
  const scheme = pendingScheme ?? endpoint.signatureScheme;
  const deliveries = deliveryPage?.content ?? [];
  const needsVerification = endpoint.verificationStatus === 'PENDING' || endpoint.verificationStatus === 'FAILED';

  /**
   * An update restates `rateLimitPerSecond` because the API reads that field unconditionally:
   * leaving it out clears the rate limit as a side effect of changing something else.
   */
  const baseUpdate = {
    url: endpoint.url,
    description: endpoint.description,
    enabled: endpoint.enabled,
    rateLimitPerSecond: endpoint.rateLimitPerSecond,
  };

  const handleTest = async () => {
    setTesting(true);
    try {
      const result = await endpointsApi.test(projectId!, endpoint.id);
      setTestResult(result);
      if (result.success) {
        showSuccess(t('endpoints.toast.testSuccess', { status: result.httpStatusCode, latency: result.latencyMs }));
      } else {
        showError(t('endpoints.toast.testFailed', { message: result.message }));
      }
    } catch (err) {
      showApiError(err, 'endpoints.toast.testError');
    } finally {
      setTesting(false);
    }
  };

  const handleToggle = async () => {
    try {
      await updateEndpoint.mutateAsync({ id: endpoint.id, data: { ...baseUpdate, enabled: !endpoint.enabled } });
      showSuccess(endpoint.enabled ? t('endpoints.toast.disabled') : t('endpoints.toast.enabled'));
      setConfirmToggle(false);
    } catch (err) {
      showApiError(err, 'endpoints.toast.toggleFailed');
    }
  };

  const handleDelete = async () => {
    try {
      await deleteEndpoint.mutateAsync(endpoint.id);
      showCriticalSuccess(t('endpoints.toast.deleted'));
      navigate(`/admin/projects/${projectId}/connections`, { replace: true });
    } catch (err) {
      showApiError(err, 'endpoints.toast.deleteFailed');
    }
  };

  const handleRotate = async () => {
    try {
      setRotated(await rotateSecret.mutateAsync(endpoint.id));
      showSuccess(t('endpoints.toast.secretRotated'));
    } catch (err) {
      showApiError(err, 'endpoints.toast.rotateFailed');
      setConfirmRotate(false);
    }
  };

  const handleVerify = async () => {
    try {
      const result = await verifyEndpoint.mutateAsync(endpoint.id);
      if (result.success) showSuccess(t('endpoints.toast.verified'));
      else if (result.reason === 'TUNNEL_OFFLINE') showError(t('endpoints.toast.verifyTunnelOffline'));
      else showError(t('endpoints.toast.verifyFailed', { message: result.message }));
    } catch (err) {
      showApiError(err, 'endpoints.toast.verifyError');
    }
  };

  const handleSkip = async () => {
    try {
      await skipVerification.mutateAsync({ id: endpoint.id, reason: t('endpointDetail.skipReason') });
      showSuccess(t('endpoints.toast.skipped'));
    } catch (err) {
      showApiError(err, 'endpoints.toast.skipFailed');
    }
  };

  const handleSchemeChange = async (next: SignatureScheme) => {
    setPendingScheme(next);
    try {
      await updateEndpoint.mutateAsync({ id: endpoint.id, data: { ...baseUpdate, signatureScheme: next } });
      showSuccess(t('signatureScheme.saved'));
    } catch (err) {
      // A scheme the endpoint does not have must not go on looking chosen.
      setPendingScheme(null);
      showApiError(err, 'signatureScheme.saveFailed');
    }
  };

  const toggleSubscription = (s: SubscriptionResponse) =>
    patchSubscription.mutate(
      { id: s.id, data: { enabled: !s.enabled } },
      { onSuccess: () => showSuccess(s.enabled ? t('subscriptions.toast.disabled') : t('subscriptions.toast.enabled')) },
    );

  const closeSecretDialog = () => {
    setConfirmRotate(false);
    setRotated(null);
  };

  const managed = (node: React.ReactElement) => (
    <PermissionGate allowed={canManageEndpoints}>
      <VerificationGate>{node}</VerificationGate>
    </PermissionGate>
  );

  const deliveriesLink = `/admin/projects/${projectId}/deliveries?endpointId=${endpoint.id}`;

  return (
    <div className="p-4 lg:p-6">
      <DetailBreadcrumb projectId={projectId} current={endpoint.url} />
      <PageHeader
        eyebrow={project?.name}
        title={endpoint.url}
        titleClassName="break-all font-mono text-lg sm:text-xl"
        description={endpoint.description}
        actions={canManageEndpoints ? (
          <>
            {managed(
              <Button variant="outline" onClick={handleTest} disabled={testing} title={t('endpointDetail.sendTestRequestHint')}>
                {testing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                {t('endpointDetail.sendTestRequest')}
              </Button>,
            )}
            {managed(
              <Button variant="outline" onClick={() => setConfirmToggle(true)}>
                {endpoint.enabled ? <PowerOff className="h-4 w-4" /> : <Power className="h-4 w-4" />}
                {endpoint.enabled ? t('common.disable') : t('common.enable')}
              </Button>,
            )}
          </>
        ) : undefined}
      />

      <div className="mb-6 grid items-start gap-4 md:grid-cols-2">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold">{t('endpointDetail.overview')}</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="space-y-2.5 text-sm">
              <Fact label={t('endpoints.status')}><EnabledBadge enabled={endpoint.enabled} /></Fact>
              <Fact label={t('endpoints.verification')}>
                <span className="flex flex-wrap items-center justify-end gap-2">
                  <StatusBadge
                    kind={verificationKind(endpoint.verificationStatus)}
                    label={t(`endpoints.${(endpoint.verificationStatus ?? 'PENDING').toLowerCase()}`)}
                  />
                  {canManageEndpoints && needsVerification && (
                    <>
                      <Button size="sm" variant="outline" onClick={handleVerify} disabled={verifyEndpoint.isPending}>
                        {verifyEndpoint.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                        {t('endpoints.verify')}
                      </Button>
                      <Button size="sm" variant="ghost" onClick={handleSkip} disabled={skipVerification.isPending}>
                        {t('endpoints.skip')}
                      </Button>
                    </>
                  )}
                </span>
              </Fact>
              <Fact label={t('endpoints.consumer')}>
                {endpoint.consumerId ? (
                  <Link to={`/admin/projects/${projectId}/consumers`} className="underline-offset-4 hover:underline">
                    {consumerNames?.get(endpoint.consumerId) ?? endpoint.consumerId.substring(0, 8)}
                  </Link>
                ) : (
                  <span className="text-muted-foreground">{t('endpoints.ownEndpoint')}</span>
                )}
              </Fact>
              <Fact label={t('connections.detailRateLimit')}>
                <span className="font-mono text-xs">{endpoint.rateLimitPerSecond ? `${endpoint.rateLimitPerSecond}/s` : '—'}</span>
              </Fact>
              <Fact label={t('endpointDetail.allowedIps')}>
                <span className="break-all font-mono text-xs">{endpoint.allowedSourceIps || '—'}</span>
              </Fact>
              <Fact label={t('connections.detailMtls')}>
                <span className="flex items-center justify-end gap-2">
                  <span className="font-mono text-xs">{endpoint.mtlsEnabled ? t('common.on') : t('common.off')}</span>
                  {managed(
                    <Button size="sm" variant="outline" onClick={() => setShowMtls(true)}>
                      <ShieldCheck className="h-3.5 w-3.5" />
                      {endpoint.mtlsEnabled ? t('endpoints.configureMtls') : t('endpoints.enableMtls')}
                    </Button>,
                  )}
                </span>
              </Fact>
              <Fact label={t('endpointDetail.created')}>
                <span className="font-mono text-xs">{formatDateTime(endpoint.createdAt)}</span>
              </Fact>
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold">{t('connections.detailSecret')}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <p className="text-sm text-muted-foreground">{t('connections.secretHidden')}</p>
            {sendsStandardHeaders(scheme) && (
              <p className="text-sm text-muted-foreground">{t('connections.standardSecretHidden')}</p>
            )}
            {managed(
              <Button variant="outline" size="sm" onClick={() => setConfirmRotate(true)}>
                <RefreshCw className="h-3.5 w-3.5" /> {t('endpoints.rotateSecret')}
              </Button>,
            )}
            <div className="pt-2">
              <SignatureSchemePicker value={scheme} onChange={handleSchemeChange} disabled={!canManageEndpoints} />
            </div>
          </CardContent>
        </Card>
      </div>

      <div className="mb-6">
        <IntegrationSnippet
          title={t('endpoints.snippet.title')}
          samples={verifySignatureSnippets({ scheme, endpointUrl: endpoint.url })}
          footer={t('endpoints.snippet.footer')}
          docsLink="outgoing/signatures"
        />
      </div>

      <section className="mb-6" aria-labelledby="endpoint-subscriptions">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 id="endpoint-subscriptions" className="text-[15px] font-medium">{t('connections.detailSubscriptions')}</h2>
            <p className="text-sm text-muted-foreground">{t('endpointDetail.subscriptionsDesc')}</p>
          </div>
          <PermissionGate allowed={canManageSubscriptions}>
            <VerificationGate>
              <Button variant="outline" size="sm" onClick={() => setSubscriptionDialog({ editing: null })}>
                <Plus className="h-3.5 w-3.5" /> {t('connections.addSubscription')}
              </Button>
            </VerificationGate>
          </PermissionGate>
        </div>
        <Card>
          <CardContent className="space-y-4 p-4">
            {subscriptions.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('connections.noSubscriptionsDesc')}</p>
            ) : (
              <ul className="divide-y divide-rail">
                {subscriptions.map((s) => (
                  <li key={s.id} className="flex items-center gap-3 py-2 first:pt-0 last:pb-0">
                    <Switch
                      checked={s.enabled}
                      disabled={!canManageSubscriptions}
                      onCheckedChange={() => toggleSubscription(s)}
                      aria-label={t('connections.toggleSubscription', { eventType: s.eventType })}
                    />
                    <code className="min-w-0 truncate font-mono text-[13px]">{s.eventType}</code>
                    {s.orderingEnabled && (
                      <Badge variant="outline" className="font-mono text-[10px]">{t('subscriptions.fifo')}</Badge>
                    )}
                    {s.transformationName && (
                      <span className="hidden truncate text-xs text-muted-foreground sm:inline">{s.transformationName}</span>
                    )}
                    {canManageSubscriptions && (
                      <Button variant="ghost" size="sm" className="ml-auto" onClick={() => setSubscriptionDialog({ editing: s })}>
                        {t('common.edit')}
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {ladderSource && (
              <div className="border-t border-rail pt-3">
                <div className="mono-label mb-2">{t('connections.detailLadder')}</div>
                <AttemptRail
                  attempts={ladderTicks(ladderSource.retryDelays ?? '', ladderSource.maxAttempts ?? 1)}
                  size="full"
                  ariaLabel={t('connections.ladderLabel', { url: endpoint.url })}
                />
              </div>
            )}
          </CardContent>
        </Card>
      </section>

      <section className="mb-6" aria-labelledby="endpoint-deliveries">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 id="endpoint-deliveries" className="text-[15px] font-medium">{t('endpointDetail.recentDeliveries')}</h2>
            <p className="text-sm text-muted-foreground">{t('endpointDetail.recentDeliveriesDesc', { count: RECENT })}</p>
          </div>
          <Link
            to={deliveriesLink}
            className="inline-flex items-center gap-1.5 text-sm text-primary underline-offset-4 hover:underline"
          >
            {t('endpointDetail.allDeliveries')}
            <ArrowRight className="h-3.5 w-3.5" aria-hidden />
          </Link>
        </div>
        {deliveriesLoading ? (
          <SkeletonRows count={3} height="h-12" />
        ) : deliveries.length === 0 ? (
          <EmptyState
            icon={Truck}
            title={t('endpointDetail.noDeliveries')}
            description={t('endpointDetail.noDeliveriesDesc')}
          />
        ) : (
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('deliveries.columns.status')}</TableHead>
                  <TableHead>{t('events.eventType')}</TableHead>
                  <TableHead>{t('deliveries.columns.attempts')}</TableHead>
                  <TableHead>{t('deliveries.columns.created')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {deliveries.map((d) => (
                  <TableRow key={d.id} className="cursor-pointer" onClick={() => setOpenDeliveryId(d.id)}>
                    <TableCell>
                      <StatusBadge kind={kindOfDeliveryStatus(d.status)} label={t(`deliveries.status.${d.status}`)} />
                    </TableCell>
                    <TableCell>
                      <button
                        type="button"
                        onClick={(e) => { e.stopPropagation(); setOpenDeliveryId(d.id); }}
                        className="rounded font-mono text-[13px] underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        {d.eventType ?? d.eventId.substring(0, 8)}
                      </button>
                    </TableCell>
                    <TableCell>
                      <span className="font-mono text-xs">{d.attemptCount}/{d.maxAttempts}</span>
                    </TableCell>
                    <TableCell><TimeCell value={d.createdAt} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        )}
      </section>

      {canManageEndpoints && (
        <section className="flex flex-col gap-3 rounded-xl border border-halt/30 p-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-[15px] font-medium">{t('endpointDetail.deleteTitle')}</h2>
            <p className="text-sm text-muted-foreground">{t('endpoints.deleteDialog.description')}</p>
          </div>
          {managed(
            <Button variant="outline" className="text-halt hover:text-halt" onClick={() => setConfirmDelete(true)}>
              <Trash2 className="h-4 w-4" /> {t('common.delete')}
            </Button>,
          )}
        </section>
      )}

      <ConfirmDialog
        open={confirmToggle}
        onOpenChange={setConfirmToggle}
        title={endpoint.enabled ? t('endpoints.toggleDialog.disableTitle') : t('endpoints.toggleDialog.enableTitle')}
        description={endpoint.enabled ? t('endpoints.toggleDialog.disableDesc') : t('endpoints.toggleDialog.enableDesc')}
        onConfirm={handleToggle}
        loading={updateEndpoint.isPending}
        confirmLabel={endpoint.enabled ? t('common.disable') : t('common.enable')}
        loadingLabel={t('endpoints.toggleDialog.processing')}
        destructive={false}
      />

      <ConfirmDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title={t('endpoints.deleteDialog.title')}
        description={t('endpoints.deleteDialog.description')}
        onConfirm={handleDelete}
        loading={deleteEndpoint.isPending}
      />

      <ConfirmDialog
        open={confirmRotate && !rotated}
        onOpenChange={(open) => !open && setConfirmRotate(false)}
        title={t('endpoints.rotateDialog.title')}
        description={t('endpoints.rotateDialog.description')}
        onConfirm={handleRotate}
        loading={rotateSecret.isPending}
        confirmLabel={t('endpoints.rotateDialog.submit')}
        loadingLabel={t('endpoints.rotateDialog.rotating')}
        destructive={false}
      />

      {/* The new secret, shown exactly once. */}
      <Dialog open={!!rotated} onOpenChange={(open) => !open && closeSecretDialog()}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t('endpoints.secretDialog.title')}</DialogTitle>
            <DialogDescription>{t('endpoints.secretDialog.description')}</DialogDescription>
          </DialogHeader>
          {rotated?.secret && <SecretField secret={rotated.secret} label={t('connectionSetup.secret.label')} />}
          {/* The `whsec_…` form is the one a Standard Webhooks library takes, and only worth
              showing to an endpoint that is sent those headers. */}
          {rotated?.standardWebhooksSecret && sendsStandardHeaders(rotated.signatureScheme) && (
            <>
              <SecretField secret={rotated.standardWebhooksSecret} label={t('connectionSetup.secret.standardLabel')} />
              <p className="text-xs text-muted-foreground">{t('connectionSetup.secret.standardHint')}</p>
            </>
          )}
          <p className="text-sm text-muted-foreground">{t('endpoints.secretDialog.hint')}</p>
          <DialogFooter>
            <Button onClick={closeSecretDialog}>{t('endpoints.secretDialog.done')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!testResult} onOpenChange={(open) => !open && setTestResult(null)}>
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
                <p className="rounded-md border border-halt/30 bg-halt-soft p-3 text-sm text-halt">{testResult.errorMessage}</p>
              </div>
            )}
          </div>
          <DialogFooter>
            <Button onClick={() => setTestResult(null)}>{t('common.close')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {showMtls && (
        <MtlsConfigModal
          open={showMtls}
          onOpenChange={setShowMtls}
          projectId={projectId!}
          endpoint={endpoint}
          onUpdate={() => {
            qc.invalidateQueries({ queryKey: ['endpoints', projectId] });
            setShowMtls(false);
          }}
        />
      )}

      {subscriptionDialog && (
        <CreateSubscriptionModal
          projectId={projectId!}
          endpoints={endpoints.length > 0 ? endpoints : [endpoint]}
          subscription={subscriptionDialog.editing}
          defaultEndpointId={endpoint.id}
          open
          onClose={() => setSubscriptionDialog(null)}
          onSuccess={() => qc.invalidateQueries({ queryKey: queryKeys.subscriptions.list(projectId!) })}
        />
      )}

      <DeliveryDetailsSheet
        deliveryId={openDeliveryId}
        open={!!openDeliveryId}
        onClose={() => setOpenDeliveryId(null)}
        onRefresh={refetchDeliveries}
      />
    </div>
  );
}
