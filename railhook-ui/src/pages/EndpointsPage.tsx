import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ChevronRight, Plus, Webhook, Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showError, showSuccess } from '../lib/toast';
import { formatDate } from '../lib/date';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge, { EnabledBadge, type StatusKind } from '../components/StatusBadge';
import {
  useProject, useEndpointsPaged, useCreateEndpoint, useVerifyEndpoint, useSkipVerification, useConsumerNames,
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
import SignatureSchemePicker from '../components/SignatureSchemePicker';
import SecretField from '../components/SecretField';
import IntegrationSnippet from '../components/IntegrationSnippet';
import { verifySignatureSnippets } from '../lib/integrationSnippets';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';

/**
 * The flat list of Endpoints — one half of a connection, for the times you
 * want to work on endpoints as endpoints: add one without subscribing it to
 * anything yet, or find the one whose verification never landed. Connections
 * is where the two halves are seen together, and a row here opens the
 * endpoint's own page, which is where it is tested, turned off or deleted —
 * those used to be a row of five unlabelled icons at the end of each row.
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
  const navigate = useNavigate();

  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [url, setUrl] = useState('');
  const [description, setDescription] = useState('');
  const [rateLimitPerSecond, setRateLimitPerSecond] = useState<number | undefined>(undefined);
  const [allowedSourceIps, setAllowedSourceIps] = useState('');
  const [signatureScheme, setSignatureScheme] = useState<SignatureScheme>('BOTH');
  const [newSecret, setNewSecret] = useState<string | null>(null);
  // Which endpoint the secret on screen belongs to, so the verification code next to it is routed
  // on that endpoint's path and checks the headers it is actually sent.
  const [secretOwner, setSecretOwner] = useState<{ url?: string; scheme?: SignatureScheme } | null>(null);
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

  const displayEndpoints = pageInfo?.content ?? [];
  // Only a project that hands endpoints to its own users has a Consumer column to show.
  const showConsumers = displayEndpoints.some((e) => e.consumerId);
  const { data: consumerNames } = useConsumerNames(projectId, showConsumers);

  // First load only; a page change keeps the previous rows (keepPreviousData).
  const loading = (projectLoading && !project) || (endpointsLoading && !pageInfo);
  const isError = projectIsError || endpointsIsError;
  const retry = () => { refetchProject(); refetchEndpoints(); };

  const createEndpoint = useCreateEndpoint(projectId!);
  const verifyEndpoint = useVerifyEndpoint(projectId!);
  const skipVerification = useSkipVerification(projectId!);
  const creating = createEndpoint.isPending;

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
      setSecretOwner({ url, scheme: signatureScheme });
      setNewSecret(secret);
      showSuccess(t('endpoints.toast.created'));
    } catch (err) {
      showApiError(err, 'endpoints.toast.createFailed');
    }
  };



  const closeSecretDialog = () => {
    setNewSecret(null);
    setSecretOwner(null);
  };

  const handleVerify = async (endpointId: string) => {
    setVerifyingId(endpointId);
    try {
      const result = await verifyEndpoint.mutateAsync(endpointId);
      if (result.success) showSuccess(t('endpoints.toast.verified'));
      else if (result.reason === 'TUNNEL_OFFLINE') showError(t('endpoints.toast.verifyTunnelOffline'));
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
        guide={{
          id: 'endpoints',
          docsLink: 'outgoing/endpoints-subscriptions',
          children: (
            <IntegrationSnippet
              title={t('endpoints.snippet.title')}
              samples={verifySignatureSnippets({})}
              footer={t('endpoints.snippet.footer')}
              docsLink="outgoing/signatures"
            />
          ),
        }}
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
          docsLink="outgoing/endpoints-subscriptions"
        />
      ) : (
        <>
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('endpoints.url')}</TableHead>
                  {showConsumers && <TableHead>{t('endpoints.consumer')}</TableHead>}
                  <TableHead>{t('endpoints.verification')}</TableHead>
                  <TableHead>{t('endpoints.status')}</TableHead>
                  <TableHead>{t('subscriptions.created')}</TableHead>
                  <TableHead className="w-[36px]"><span className="sr-only">{t('connections.open')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {displayEndpoints.map((endpoint) => (
                  <TableRow
                    key={endpoint.id}
                    className="group/row cursor-pointer"
                    onClick={() => navigate(`/admin/projects/${projectId}/endpoints/${endpoint.id}`)}
                  >
                    <TableCell data-card-title className="max-w-[320px]">
                      <Link
                        to={`/admin/projects/${projectId}/endpoints/${endpoint.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="block truncate rounded font-mono text-[13px] underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        title={endpoint.url}
                      >
                        {endpoint.url}
                      </Link>
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
                    {showConsumers && (
                      <TableCell>
                        {endpoint.consumerId ? (
                          <Link
                            to={`/admin/projects/${projectId}/consumers`}
                            onClick={(e) => e.stopPropagation()}
                            className="text-[13px] underline-offset-4 hover:underline"
                          >
                            {consumerNames?.get(endpoint.consumerId) ?? endpoint.consumerId.substring(0, 8)}
                          </Link>
                        ) : (
                          <span className="text-[13px] text-muted-foreground">{t('endpoints.ownEndpoint')}</span>
                        )}
                      </TableCell>
                    )}
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
                              onClick={(e) => { e.stopPropagation(); handleVerify(endpoint.id); }}
                              disabled={verifyingId === endpoint.id}
                            >
                              {verifyingId === endpoint.id && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                              {t('endpoints.verify')}
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={(e) => { e.stopPropagation(); handleSkipVerification(endpoint.id); }}
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
                      <ChevronRight className="h-4 w-4 text-muted-foreground group-hover/row:text-foreground" aria-hidden />
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


      {/* The secret is shown once, masked until asked for. */}
      <Dialog open={!!newSecret} onOpenChange={closeSecretDialog}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t('endpoints.secretDialog.title')}</DialogTitle>
            <DialogDescription>{t('endpoints.secretDialog.description')}</DialogDescription>
          </DialogHeader>
          {newSecret && <SecretField secret={newSecret} />}
          <p className="text-sm text-muted-foreground">{t('endpoints.secretDialog.hint')}</p>
          <IntegrationSnippet
            title={t('endpoints.snippet.title')}
            samples={verifySignatureSnippets({ scheme: secretOwner?.scheme, endpointUrl: secretOwner?.url })}
            footer={t('endpoints.snippet.footer')}
            docsLink="outgoing/signatures"
          />
          <DialogFooter>
            <Button onClick={closeSecretDialog}>{t('endpoints.secretDialog.done')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
