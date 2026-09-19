import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowDownToLine, ArrowRight, ChevronRight, Loader2, Network, Plus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showError, showSuccess } from '../lib/toast';
import {
  useConsumerNames, useDeliveries, useEndpoints, useIncomingConnections, useProject,
  useSubscriptions, useVerifyEndpoint,
} from '../api/queries';
import type { DeliveryResponse, EndpointResponse } from '../types/api.types';
import { PROVIDER_NAMES } from '../lib/publicSnippets';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge, { EnabledBadge, type StatusKind } from '../components/StatusBadge';
import ConnectionSetupDialog from '../components/ConnectionSetupDialog';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';

/**
 * Connections — the one list of where events go, a row per endpoint with what it is subscribed
 * to and whether its last deliveries arrived; and, below it, the incoming counterpart, a row per
 * source with the destinations it forwards to.
 *
 * A row opens the endpoint's page, which is where the endpoint is configured and acted on. The
 * flat Endpoints and Subscriptions tables used to sit beside this as tabs of their own, which made
 * three places to answer "where do my events go"; they are linked from here instead, for the
 * times one half is worked on alone.
 */

/** How many recent deliveries the health column is scored over. */
const HEALTH_WINDOW = 100;

interface Health {
  total: number;
  ok: number;
  kind: StatusKind;
}

function scoreHealth(deliveries: DeliveryResponse[]): Health {
  let ok = 0;
  let failing = 0;
  let pending = 0;
  for (const d of deliveries) {
    if (d.status === 'SUCCESS') ok++;
    else if (d.status === 'DLQ' || d.status === 'FAILED') failing++;
    else pending++;
  }
  const total = deliveries.length;
  let kind: StatusKind = 'idle';
  if (total > 0) {
    if (failing > 0) kind = deliveries.some((d) => d.status === 'DLQ') ? 'halt' : 'retry';
    else if (pending > 0) kind = 'retry';
    else kind = 'ok';
  }
  return { total, ok, kind };
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

const linkClass = 'inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline';

export default function ConnectionsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const { canManageEndpoints } = usePermissions();

  const { data: project, isLoading: projectLoading } = useProject(projectId);
  const {
    data: endpoints = [], isLoading: endpointsLoading, isError: endpointsFailed,
    error: endpointsError, refetch: refetchEndpoints,
  } = useEndpoints(projectId);
  const {
    data: subscriptions = [], isError: subsFailed, error: subsError, refetch: refetchSubs,
  } = useSubscriptions(projectId);
  const { data: deliveryPage } = useDeliveries(projectId, { size: HEALTH_WINDOW, sort: 'createdAt,desc' });
  const incoming = useIncomingConnections(projectId);
  const showConsumers = endpoints.some((e) => e.consumerId);
  const { data: consumerNames } = useConsumerNames(projectId, showConsumers);
  const verifyEndpoint = useVerifyEndpoint(projectId!);

  const [showSetup, setShowSetup] = useState(false);
  const [verifyingId, setVerifyingId] = useState<string | null>(null);

  const rows = useMemo(() => {
    const deliveries = deliveryPage?.content ?? [];
    return endpoints.map((endpoint) => ({
      endpoint,
      subs: subscriptions.filter((s) => s.endpointId === endpoint.id),
      health: scoreHealth(deliveries.filter((d) => d.endpointId === endpoint.id)),
    }));
  }, [endpoints, subscriptions, deliveryPage]);

  const loading = projectLoading || endpointsLoading;
  const failed = endpointsFailed || subsFailed;
  const base = `/admin/projects/${projectId}`;
  const openEndpoint = (endpoint: EndpointResponse) => navigate(`${base}/endpoints/${endpoint.id}`);

  const handleVerify = async (endpoint: EndpointResponse) => {
    setVerifyingId(endpoint.id);
    try {
      const result = await verifyEndpoint.mutateAsync(endpoint.id);
      if (result.success) showSuccess(t('endpoints.toast.verified'));
      else if (result.reason === 'TUNNEL_OFFLINE') showError(t('endpoints.toast.verifyTunnelOffline'));
      else showError(t('endpoints.toast.verifyFailed', { message: result.message }));
    } catch (err) {
      showApiError(err, 'endpoints.toast.verifyError');
    } finally {
      setVerifyingId(null);
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
        description={t('connections.descriptionMap')}
        actions={rows.length > 0 ? newConnectionButton : undefined}
      />

      <section aria-labelledby="connections-outgoing" className="mb-8">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
          <div>
            <h2 id="connections-outgoing" className="text-[15px] font-medium">{t('nav.outgoing')}</h2>
            <p className="text-sm text-muted-foreground">{t('connections.outgoingDesc')}</p>
          </div>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <Link to={`${base}/endpoints`} className={linkClass}>{t('connections.allEndpoints')}</Link>
            <Link to={`${base}/subscriptions`} className={linkClass}>{t('connections.allSubscriptions')}</Link>
          </div>
        </div>

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
            description={t('connections.emptyDescNew')}
            action={newConnectionButton}
            docsLink="outgoing/endpoints-subscriptions"
          />
        ) : (
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('connections.columnEndpoint')}</TableHead>
                  <TableHead>{t('connections.columnSubscribedTo')}</TableHead>
                  <TableHead>{t('connections.columnRecent')}</TableHead>
                  <TableHead>{t('endpoints.verification')}</TableHead>
                  <TableHead>{t('endpoints.status')}</TableHead>
                  <TableHead className="hidden w-[36px] sm:table-cell"><span className="sr-only">{t('connections.open')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map(({ endpoint, subs, health }) => (
                  <TableRow key={endpoint.id} className="group/row cursor-pointer" onClick={() => openEndpoint(endpoint)}>
                    <TableCell data-card-title className="max-w-[300px]">
                      <Link
                        to={`${base}/endpoints/${endpoint.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="block truncate rounded font-mono text-[13px] underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        title={endpoint.url}
                      >
                        {endpoint.url}
                      </Link>
                      {(endpoint.description || endpoint.consumerId) && (
                        <div className="truncate text-xs text-muted-foreground">
                          {endpoint.consumerId
                            ? consumerNames?.get(endpoint.consumerId) ?? endpoint.description
                            : endpoint.description}
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="max-w-[220px]">
                      {subs.length === 0 ? (
                        <span className="text-xs text-muted-foreground">{t('connections.noSubscriptions')}</span>
                      ) : (
                        <div className="flex flex-wrap items-center gap-1">
                          {subs.slice(0, 2).map((s) => (
                            <Badge key={s.id} variant="outline" className="font-mono text-[11px]">{s.eventType}</Badge>
                          ))}
                          {subs.length > 2 && (
                            <span className="font-mono text-[11px] text-muted-foreground">+{subs.length - 2}</span>
                          )}
                        </div>
                      )}
                    </TableCell>
                    <TableCell>
                      {health.total === 0 ? (
                        <StatusBadge kind="idle" label={t('connections.health.none')} />
                      ) : (
                        <div className="flex items-center gap-2">
                          <StatusBadge
                            kind={health.kind}
                            label={
                              health.kind === 'ok'
                                ? t('connections.health.ok')
                                : health.kind === 'retry'
                                  ? t('connections.health.retry')
                                  : t('connections.health.halt')
                            }
                          />
                          <span className="font-mono text-[11px] text-muted-foreground">{health.ok}/{health.total}</span>
                        </div>
                      )}
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap items-center gap-2">
                        <StatusBadge
                          kind={verificationKind(endpoint.verificationStatus)}
                          label={t(`endpoints.${(endpoint.verificationStatus ?? 'PENDING').toLowerCase()}`)}
                        />
                        {canManageEndpoints && endpoint.verificationStatus !== 'VERIFIED' && endpoint.verificationStatus !== 'SKIPPED' && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={(e) => { e.stopPropagation(); handleVerify(endpoint); }}
                            disabled={verifyingId === endpoint.id}
                          >
                            {verifyingId === endpoint.id && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                            {t('endpoints.verify')}
                          </Button>
                        )}
                      </div>
                    </TableCell>
                    <TableCell><EnabledBadge enabled={endpoint.enabled} /></TableCell>
                    <TableCell className="hidden sm:table-cell">
                      <ChevronRight className="h-4 w-4 text-muted-foreground group-hover/row:text-foreground" aria-hidden />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        )}
      </section>

      <section aria-labelledby="connections-incoming">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
          <div>
            <h2 id="connections-incoming" className="text-[15px] font-medium">{t('nav.incoming')}</h2>
            <p className="text-sm text-muted-foreground">{t('connections.incomingDesc')}</p>
          </div>
          {incoming.rows.length > 0 && (
            <Link to={`${base}/incoming-sources`} className={linkClass}>{t('connections.allSources')}</Link>
          )}
        </div>

        {incoming.isLoading ? (
          <SkeletonRows count={2} height="h-12" />
        ) : incoming.isError ? (
          <p className="text-sm text-muted-foreground">{t('connections.incomingFailed')}</p>
        ) : incoming.rows.length === 0 ? (
          <div className="flex flex-col gap-3 rounded-xl border border-dashed border-rail p-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-start gap-3">
              <ArrowDownToLine className="mt-0.5 h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />
              <p className="text-sm text-muted-foreground">{t('connections.noSources')}</p>
            </div>
            <Link to={`${base}/incoming-sources`} className={linkClass}>
              {t('connections.addSource')} <ArrowRight className="h-3.5 w-3.5" aria-hidden />
            </Link>
          </div>
        ) : (
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('connections.columnSource')}</TableHead>
                  <TableHead>{t('connections.columnForwardsTo')}</TableHead>
                  <TableHead>{t('endpoints.status')}</TableHead>
                  <TableHead className="hidden w-[36px] sm:table-cell"><span className="sr-only">{t('connections.open')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {incoming.rows.map(({ source, destinations, loading: destinationsLoading }) => (
                  <TableRow
                    key={source.id}
                    className="group/row cursor-pointer"
                    onClick={() => navigate(`${base}/incoming-sources/${source.id}`)}
                  >
                    <TableCell data-card-title>
                      <Link
                        to={`${base}/incoming-sources/${source.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="rounded text-[13px] font-medium underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        {source.name}
                      </Link>
                      <div className="text-xs text-muted-foreground">{PROVIDER_NAMES[source.providerType] ?? source.providerType}</div>
                    </TableCell>
                    <TableCell className="max-w-[360px]">
                      {destinationsLoading ? (
                        <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" aria-hidden />
                      ) : destinations.length === 0 ? (
                        <span className="text-xs text-muted-foreground">{t('connections.noDestinations')}</span>
                      ) : (
                        <div className="space-y-0.5">
                          {destinations.slice(0, 2).map((d) => (
                            <div key={d.id} className="truncate font-mono text-[12px]" title={d.url}>{d.url}</div>
                          ))}
                          {destinations.length > 2 && (
                            <span className="font-mono text-[11px] text-muted-foreground">+{destinations.length - 2}</span>
                          )}
                        </div>
                      )}
                    </TableCell>
                    <TableCell><EnabledBadge enabled={source.status === 'ACTIVE'} /></TableCell>
                    <TableCell className="hidden sm:table-cell">
                      <ChevronRight className="h-4 w-4 text-muted-foreground group-hover/row:text-foreground" aria-hidden />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        )}
      </section>

      <ConnectionSetupDialog projectId={projectId} open={showSetup} onOpenChange={setShowSetup} />
    </div>
  );
}
