import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  ChevronDown, ChevronRight, Loader2, Network, Plus, Power, PowerOff, RefreshCw,
  Send, ShieldCheck,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { showApiError, showError, showSuccess } from '../lib/toast';
import { endpointsApi } from '../api/endpoints.api';
import type { SubscriptionResponse } from '../api/subscriptions.api';
import {
  queryKeys, useDeliveries, useEndpoints, usePatchSubscription, useProject,
  useRotateSecret, useSubscriptions, useUpdateEndpoint, useVerifyEndpoint,
} from '../api/queries';
import type { DeliveryResponse, EndpointResponse, SignatureScheme } from '../types/api.types';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge, { EnabledBadge, type StatusKind } from '../components/StatusBadge';
import AttemptRail from '../components/AttemptRail';
import CreateSubscriptionModal from '../components/CreateSubscriptionModal';
import { SecretField, ladderTicks } from './ConnectionSetupPage';
import ConnectionSetupDialog from '../components/ConnectionSetupDialog';
import SignatureSchemePicker, { sendsStandardHeaders } from '../components/SignatureSchemePicker';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Switch } from '../components/ui/switch';
import { Badge } from '../components/ui/badge';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { cn } from '../lib/utils';

/**
 * Connections — one row per endpoint, holding everything that decides where
 * that endpoint's events go and whether they are arriving.
 *
 * The section used to be four destinations for one job: a wizard that created
 * an endpoint plus its subscriptions, a read-only matrix of the same pairs, and
 * a flat list of each half. This is the one that answers the question a person
 * actually arrives with — "where do my events go, and is that working" — and
 * the flat lists stay as sibling tabs for the times you want to work on one
 * half at a time.
 *
 * A row groups: the endpoint (URL, enabled, verification, signing secret) and
 * every Subscription hanging off it, scored against the deliveries those
 * subscriptions have recently produced.
 */

/** How many recent deliveries the health column is scored over. */
const HEALTH_WINDOW = 100;

interface Health {
  total: number;
  ok: number;
  failing: number;
  pending: number;
  kind: StatusKind;
}

function scoreHealth(deliveries: DeliveryResponse[]): Health {
  let ok = 0;
  let failing = 0;
  let pending = 0;
  for (const d of deliveries) {
    if (d.status === 'SUCCESS') ok++;
    else if (d.status === 'DLQ') failing++;
    else if (d.status === 'FAILED') failing++;
    else pending++;
  }
  const total = deliveries.length;
  let kind: StatusKind = 'idle';
  if (total > 0) {
    if (failing > 0) kind = deliveries.some((d) => d.status === 'DLQ') ? 'halt' : 'retry';
    else if (pending > 0) kind = 'retry';
    else kind = 'ok';
  }
  return { total, ok, failing, pending, kind };
}

/** Verification is a configuration state, mapped onto the four status meanings. */
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

export default function ConnectionsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const qc = useQueryClient();
  const { canManageEndpoints, canManageSubscriptions } = usePermissions();

  const { data: project, isLoading: projectLoading } = useProject(projectId);
  const {
    data: endpoints = [], isLoading: endpointsLoading, isError: endpointsFailed,
    error: endpointsError, refetch: refetchEndpoints,
  } = useEndpoints(projectId);
  const {
    data: subscriptions = [], isError: subsFailed, error: subsError, refetch: refetchSubs,
  } = useSubscriptions(projectId);
  const { data: deliveryPage } = useDeliveries(projectId, { size: HEALTH_WINDOW, sort: 'createdAt,desc' });

  const updateEndpoint = useUpdateEndpoint(projectId!);
  const rotateSecret = useRotateSecret(projectId!);
  const verifyEndpoint = useVerifyEndpoint(projectId!);
  const patchSubscription = usePatchSubscription(projectId!);

  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [showSetup, setShowSetup] = useState(false);
  const [rotateFor, setRotateFor] = useState<EndpointResponse | null>(null);
  // The whole response, not just the secret: rotation is the other moment the
  // `whsec_…` form is returned, and which of the two a reader needs depends on
  // the scheme the response also carries.
  const [rotated, setRotated] = useState<EndpointResponse | null>(null);
  // The scheme a person just chose, held until the endpoints query catches up.
  // Without it the radio springs back to the old option for the length of a
  // refetch, which reads as the click having failed.
  const [pendingScheme, setPendingScheme] = useState<Record<string, SignatureScheme>>({});
  const [subscribeTo, setSubscribeTo] = useState<string | null>(null);
  const [editingSubscription, setEditingSubscription] = useState<SubscriptionResponse | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [verifyingId, setVerifyingId] = useState<string | null>(null);

  const rows = useMemo(() => {
    const deliveries = deliveryPage?.content ?? [];
    return endpoints.map((endpoint) => {
      const subs = subscriptions.filter((s) => s.endpointId === endpoint.id);
      const health = scoreHealth(deliveries.filter((d) => d.endpointId === endpoint.id));
      return { endpoint, subs, health };
    });
  }, [endpoints, subscriptions, deliveryPage]);

  const loading = projectLoading || endpointsLoading;
  const failed = endpointsFailed || subsFailed;

  const toggleExpanded = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const handleToggleEndpoint = async (endpoint: EndpointResponse) => {
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
    } catch (err) {
      showApiError(err, 'endpoints.toast.toggleFailed');
    }
  };

  const handleTest = async (endpoint: EndpointResponse) => {
    if (!projectId) return;
    setTestingId(endpoint.id);
    try {
      const result = await endpointsApi.test(projectId, endpoint.id);
      if (result.success) {
        showSuccess(t('endpoints.toast.testSuccess', { status: result.httpStatusCode, latency: result.latencyMs }));
      } else {
        showError(t('endpoints.toast.testFailed', { message: result.message }));
      }
    } catch (err) {
      showApiError(err, 'endpoints.toast.testError');
    } finally {
      setTestingId(null);
    }
  };

  const handleVerify = async (endpoint: EndpointResponse) => {
    setVerifyingId(endpoint.id);
    try {
      const result = await verifyEndpoint.mutateAsync(endpoint.id);
      if (result.success) showSuccess(t('endpoints.toast.verified'));
      else showError(t('endpoints.toast.verifyFailed', { message: result.message }));
    } catch (err) {
      showApiError(err, 'endpoints.toast.verifyError');
    } finally {
      setVerifyingId(null);
    }
  };

  const handleRotate = async () => {
    if (!rotateFor) return;
    try {
      const updated = await rotateSecret.mutateAsync(rotateFor.id);
      setRotated(updated);
      showSuccess(t('endpoints.toast.secretRotated'));
    } catch (err) {
      showApiError(err, 'endpoints.toast.rotateFailed');
      setRotateFor(null);
    }
  };

  const closeSecretDialog = () => {
    setRotateFor(null);
    setRotated(null);
  };

  /**
   * The update carries `rateLimitPerSecond` because the API reads that field
   * unconditionally — an update that leaves it out clears the endpoint's rate
   * limit as a side effect of changing something else entirely.
   */
  const handleSchemeChange = async (endpoint: EndpointResponse, scheme: SignatureScheme) => {
    setPendingScheme((prev) => ({ ...prev, [endpoint.id]: scheme }));
    try {
      await updateEndpoint.mutateAsync({
        id: endpoint.id,
        data: {
          url: endpoint.url,
          description: endpoint.description,
          enabled: endpoint.enabled,
          rateLimitPerSecond: endpoint.rateLimitPerSecond,
          signatureScheme: scheme,
        },
      });
      showSuccess(t('signatureScheme.saved'));
    } catch (err) {
      // A scheme the endpoint does not have must not go on looking chosen.
      setPendingScheme((prev) => {
        const next = { ...prev };
        delete next[endpoint.id];
        return next;
      });
      showApiError(err, 'signatureScheme.saveFailed');
    }
  };

  const newConnectionButton = (
    <PermissionGate allowed={canManageEndpoints}>
      <VerificationGate>
        <Button onClick={() => setShowSetup(true)}>
          <Plus className="h-4 w-4" /> {t('connections.newConnection')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  if (loading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={4} height="h-16" />
      </PageSkeleton>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={project?.name}
        title={t('connections.title')}
        description={t('connections.description', 'Every endpoint that receives this project’s events, what it is subscribed to, and whether the last deliveries arrived.')}
        actions={rows.length > 0 ? newConnectionButton : undefined}
      />

      {failed ? (
        <ErrorState
          error={endpointsError ?? subsError}
          fallbackKey="endpoints.toast.loadFailed"
          onRetry={() => { refetchEndpoints(); refetchSubs(); }}
        />
      ) : rows.length === 0 ? (
        <EmptyState
          icon={Network}
          title={t('connections.empty')}
          description={t('connections.emptyDescNew', 'A connection is an endpoint plus the event types it is subscribed to. Create the first one and events start flowing to it.')}
          action={newConnectionButton}
          docsLink="/docs#endpoints-api"
        />
      ) : (
        <Card className="overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[36px]"><span className="sr-only">{t('connections.expand', 'Show details')}</span></TableHead>
                <TableHead>{t('connections.columnEndpoint', 'Endpoint')}</TableHead>
                <TableHead>{t('connections.columnSubscribedTo', 'Subscribed to')}</TableHead>
                <TableHead>{t('connections.columnRecent', 'Recent deliveries')}</TableHead>
                <TableHead>{t('endpoints.verification')}</TableHead>
                <TableHead>{t('endpoints.status')}</TableHead>
                <TableHead className="w-[120px] text-right"><span className="sr-only">{t('common.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map(({ endpoint, subs, health }) => {
                const open = expanded.has(endpoint.id);
                const ladderSource = subs[0];
                const scheme = pendingScheme[endpoint.id] ?? endpoint.signatureScheme;
                return [
                  <TableRow key={endpoint.id} className={cn(open && 'bg-secondary/40')}>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        onClick={() => toggleExpanded(endpoint.id)}
                        aria-expanded={open}
                        aria-label={t('connections.expandFor', 'Details for {{url}}', { url: endpoint.url })}
                      >
                        {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                      </Button>
                    </TableCell>
                    <TableCell className="max-w-[280px]">
                      <div className="truncate font-mono text-[13px]" title={endpoint.url}>{endpoint.url}</div>
                      {endpoint.description && (
                        <div className="truncate text-xs text-muted-foreground">{endpoint.description}</div>
                      )}
                    </TableCell>
                    <TableCell className="max-w-[220px]">
                      {subs.length === 0 ? (
                        <span className="text-xs text-muted-foreground">{t('connections.noSubscriptions')}</span>
                      ) : (
                        <div className="flex flex-wrap items-center gap-1">
                          {subs.slice(0, 2).map((s) => (
                            <Badge key={s.id} variant="outline" className="font-mono text-[11px]">
                              {s.eventType}
                            </Badge>
                          ))}
                          {subs.length > 2 && (
                            <span className="font-mono text-[11px] text-muted-foreground">
                              +{subs.length - 2}
                            </span>
                          )}
                        </div>
                      )}
                    </TableCell>
                    <TableCell>
                      {health.total === 0 ? (
                        <StatusBadge kind="idle" label={t('connections.health.none', 'No traffic yet')} />
                      ) : (
                        <div className="flex items-center gap-2">
                          <StatusBadge
                            kind={health.kind}
                            label={
                              health.kind === 'ok'
                                ? t('connections.health.ok', 'Arriving')
                                : health.kind === 'retry'
                                  ? t('connections.health.retry', 'Retrying')
                                  : t('connections.health.halt', 'Abandoned')
                            }
                          />
                          <span className="font-mono text-[11px] text-muted-foreground">
                            {health.ok}/{health.total}
                          </span>
                        </div>
                      )}
                    </TableCell>
                    <TableCell>
                      <StatusBadge
                        kind={verificationKind(endpoint.verificationStatus)}
                        label={t(`endpoints.${(endpoint.verificationStatus ?? 'PENDING').toLowerCase()}`)}
                      />
                    </TableCell>
                    <TableCell>
                      <EnabledBadge enabled={endpoint.enabled} />
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-1">
                        {canManageEndpoints && (
                          <>
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              onClick={() => handleTest(endpoint)}
                              disabled={testingId === endpoint.id}
                              title={t('connections.sendTest', 'Send test event')}
                              aria-label={t('connections.sendTest', 'Send test event')}
                            >
                              {testingId === endpoint.id
                                ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
                                : <Send className="h-3.5 w-3.5" />}
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              onClick={() => handleToggleEndpoint(endpoint)}
                              title={endpoint.enabled ? t('common.disable') : t('common.enable')}
                              aria-label={endpoint.enabled ? t('common.disable') : t('common.enable')}
                            >
                              {endpoint.enabled ? <PowerOff className="h-3.5 w-3.5" /> : <Power className="h-3.5 w-3.5" />}
                            </Button>
                            {endpoint.verificationStatus !== 'VERIFIED' && (
                              <Button
                                variant="ghost"
                                size="icon-sm"
                                onClick={() => handleVerify(endpoint)}
                                disabled={verifyingId === endpoint.id}
                                title={t('endpoints.verifyEndpoint')}
                                aria-label={t('endpoints.verifyEndpoint')}
                              >
                                {verifyingId === endpoint.id
                                  ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
                                  : <ShieldCheck className="h-3.5 w-3.5" />}
                              </Button>
                            )}
                          </>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>,

                  open && (
                    <TableRow key={`${endpoint.id}-detail`} className="hover:bg-transparent">
                      <TableCell colSpan={7} className="bg-secondary/30 p-0">
                        <div className="grid gap-6 p-5 lg:grid-cols-2">
                          {/* Subscriptions on this endpoint */}
                          <div className="space-y-3">
                            <div className="mono-label">{t('connections.detailSubscriptions', 'Subscriptions')}</div>
                            {subs.length === 0 ? (
                              <p className="text-sm text-muted-foreground">
                                {t('connections.noSubscriptionsDesc', 'Nothing is subscribed, so this endpoint receives no events.')}
                              </p>
                            ) : (
                              <ul className="space-y-2">
                                {subs.map((s) => (
                                  <li key={s.id} className="flex items-center gap-3">
                                    <Switch
                                      checked={s.enabled}
                                      disabled={!canManageSubscriptions}
                                      onCheckedChange={() =>
                                        patchSubscription.mutate(
                                          { id: s.id, data: { enabled: !s.enabled } },
                                          {
                                            onSuccess: () => showSuccess(
                                              s.enabled ? t('subscriptions.toast.disabled') : t('subscriptions.toast.enabled')
                                            ),
                                          }
                                        )
                                      }
                                      aria-label={t('connections.toggleSubscription', 'Enable {{eventType}}', { eventType: s.eventType })}
                                    />
                                    <code className="font-mono text-[13px]">{s.eventType}</code>
                                    {s.orderingEnabled && (
                                      <Badge variant="outline" className="font-mono text-[10px]">{t('subscriptions.fifo')}</Badge>
                                    )}
                                    <Button
                                      variant="ghost"
                                      size="sm"
                                      className="ml-auto"
                                      onClick={() => { setEditingSubscription(s); setSubscribeTo(endpoint.id); }}
                                    >
                                      {t('common.edit')}
                                    </Button>
                                  </li>
                                ))}
                              </ul>
                            )}
                            <PermissionGate allowed={canManageSubscriptions}>
                              <VerificationGate>
                                <Button
                                  variant="outline"
                                  size="sm"
                                  onClick={() => { setEditingSubscription(null); setSubscribeTo(endpoint.id); }}
                                >
                                  <Plus className="h-3.5 w-3.5" /> {t('connections.addSubscription', 'Subscribe to an event type')}
                                </Button>
                              </VerificationGate>
                            </PermissionGate>

                            {ladderSource && (
                              <div className="pt-2">
                                <div className="mono-label mb-2">{t('connections.detailLadder', 'Retry ladder')}</div>
                                <AttemptRail
                                  attempts={ladderTicks(ladderSource.retryDelays ?? '', ladderSource.maxAttempts ?? 1)}
                                  size="full"
                                  ariaLabel={t('connections.ladderLabel', 'Retry ladder for {{url}}', { url: endpoint.url })}
                                />
                              </div>
                            )}
                          </div>

                          {/* Signing secret */}
                          <div className="space-y-3">
                            <div className="mono-label">{t('connections.detailSecret', 'Signing secret')}</div>
                            <p className="text-sm text-muted-foreground">
                              {t('connections.secretHidden', 'The secret is stored hashed and is shown once, when it is created or rotated. Rotate it if it may have leaked — the old secret stops working immediately.')}
                            </p>
                            {sendsStandardHeaders(scheme) && (
                              <p className="text-sm text-muted-foreground">
                                {t('connections.standardSecretHidden')}
                              </p>
                            )}
                            <PermissionGate allowed={canManageEndpoints}>
                              <VerificationGate>
                                <Button variant="outline" size="sm" onClick={() => setRotateFor(endpoint)}>
                                  <RefreshCw className="h-3.5 w-3.5" /> {t('endpoints.rotateSecret')}
                                </Button>
                              </VerificationGate>
                            </PermissionGate>

                            <div className="pt-2">
                              <SignatureSchemePicker
                                value={scheme}
                                onChange={(next) => handleSchemeChange(endpoint, next)}
                                disabled={!canManageEndpoints}
                              />
                            </div>

                            <dl className="grid grid-cols-2 gap-2 pt-2 text-xs">
                              <dt className="text-muted-foreground">{t('connections.detailRateLimit', 'Rate limit')}</dt>
                              <dd className="font-mono">
                                {endpoint.rateLimitPerSecond ? `${endpoint.rateLimitPerSecond}/s` : '—'}
                              </dd>
                              <dt className="text-muted-foreground">{t('connections.detailMtls', 'mTLS')}</dt>
                              <dd className="font-mono">
                                {endpoint.mtlsEnabled ? t('common.on') : t('common.off')}
                              </dd>
                            </dl>
                          </div>
                        </div>
                      </TableCell>
                    </TableRow>
                  ),
                ];
              })}
            </TableBody>
          </Table>
        </Card>
      )}

      <ConnectionSetupDialog projectId={projectId} open={showSetup} onOpenChange={setShowSetup} />

      {/* Rotate: confirm, then show the new secret exactly once. */}
      <AlertDialog open={!!rotateFor && !rotated} onOpenChange={(open) => !open && setRotateFor(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('endpoints.rotateDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('endpoints.rotateDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={rotateSecret.isPending}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleRotate} disabled={rotateSecret.isPending}>
              {rotateSecret.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {rotateSecret.isPending ? t('endpoints.rotateDialog.rotating') : t('endpoints.rotateDialog.submit')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Dialog open={!!rotated} onOpenChange={closeSecretDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('endpoints.secretDialog.title')}</DialogTitle>
            <DialogDescription>{t('endpoints.secretDialog.description')}</DialogDescription>
          </DialogHeader>
          {rotated?.secret && (
            <SecretField secret={rotated.secret} label={t('connectionSetup.secret.label')} />
          )}
          {/* The `whsec_…` form is the one a Standard Webhooks library takes, and
              it is only worth showing to an endpoint that is sent those headers. */}
          {rotated?.standardWebhooksSecret && sendsStandardHeaders(rotated.signatureScheme) && (
            <>
              <SecretField
                secret={rotated.standardWebhooksSecret}
                label={t('connectionSetup.secret.standardLabel')}
              />
              <p className="text-xs text-muted-foreground">{t('connectionSetup.secret.standardHint')}</p>
            </>
          )}
          <p className="text-sm text-muted-foreground">{t('endpoints.secretDialog.hint')}</p>
          <div className="flex justify-end">
            <Button onClick={closeSecretDialog}>{t('endpoints.secretDialog.done')}</Button>
          </div>
        </DialogContent>
      </Dialog>

      {projectId && (
        <CreateSubscriptionModal
          projectId={projectId}
          endpoints={endpoints}
          subscription={editingSubscription}
          defaultEndpointId={subscribeTo ?? undefined}
          open={!!subscribeTo}
          onClose={() => { setSubscribeTo(null); setEditingSubscription(null); }}
          onSuccess={() => qc.invalidateQueries({ queryKey: queryKeys.subscriptions.list(projectId) })}
        />
      )}
    </div>
  );
}
