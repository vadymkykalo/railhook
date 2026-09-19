import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Code2, ExternalLink, Loader2, Plus, Trash2, Users, Webhook } from 'lucide-react';
import {
  useConsumerEndpoints, useConsumersPaged, useCreateConsumer, useCreatePortalSession, useDeleteConsumer,
  useProject,
} from '../api/queries';
import type { ConsumerResponse, PortalSessionResponse } from '../types/api.types';
import { showApiError, showCriticalSuccess, showSuccess } from '../lib/toast';
import { formatDate, formatDateTime } from '../lib/date';
import { docsUrl } from '../lib/docsUrl';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { EnabledBadge } from '../components/StatusBadge';
import ConfirmDialog from '../components/ConfirmDialog';
import IntegrationSnippet from '../components/IntegrationSnippet';
import PortalPreview from '../components/PortalPreview';
import { consumerPortalSnippets, portalIframeSnippet } from '../lib/integrationSnippets';
import { apiBaseUrl } from '../lib/publicSnippets';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { usePermissions } from '../auth/usePermissions';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Card } from '../components/ui/card';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import {
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
} from '../components/ui/sheet';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';

/**
 * Consumers — the customer's own users — and the portal each of them can be handed.
 *
 * The everyday path is the customer's backend: it creates a Consumer when one of its users turns
 * on webhooks and opens a portal session each time that user visits the page embedding it. This
 * screen is for everything around that: seeing who has what, opening a Consumer's portal to look
 * at what they see, and getting an embed snippet to try before writing any backend code — and,
 * next to all of it, the backend code itself, since "what do I call" is the first question.
 */

/** The same shapes the API accepts for a session's allowed origin. */
const ORIGIN_PATTERN =
  /^(https:\/\/[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*|http:\/\/(?:localhost|127\.0\.0\.1))(?::[0-9]{1,5})?$/;

export default function ConsumersPage() {
  const { t, i18n } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageEndpoints } = usePermissions();

  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [creating, setCreating] = useState(false);
  const [externalId, setExternalId] = useState('');
  const [name, setName] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<ConsumerResponse | null>(null);
  const [endpointsOf, setEndpointsOf] = useState<ConsumerResponse | null>(null);
  const [embedFor, setEmbedFor] = useState<ConsumerResponse | null>(null);
  const [allowedOrigin, setAllowedOrigin] = useState('');
  const [embedSession, setEmbedSession] = useState<PortalSessionResponse | null>(null);
  const [openingId, setOpeningId] = useState<string | null>(null);

  const project = useProject(projectId);
  const consumers = useConsumersPaged(projectId, page, pageSize);
  const consumerEndpoints = useConsumerEndpoints(projectId, endpointsOf?.id ?? null);
  const createConsumer = useCreateConsumer(projectId!);
  const deleteConsumer = useDeleteConsumer(projectId!);
  const createSession = useCreatePortalSession(projectId!);

  const loading = (project.isLoading && !project.data) || (consumers.isLoading && !consumers.data);
  const list = consumers.data?.content ?? [];

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await createConsumer.mutateAsync({ externalId: externalId.trim(), name: name.trim() || undefined });
      setCreating(false);
      setExternalId('');
      setName('');
      showSuccess(t('consumers.toast.created'));
    } catch (err) {
      showApiError(err, 'consumers.toast.createFailed');
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteConsumer.mutateAsync(deleteTarget.id);
      setDeleteTarget(null);
      showCriticalSuccess(t('consumers.toast.deleted'));
    } catch (err) {
      showApiError(err, 'consumers.toast.deleteFailed');
    }
  };

  /*
   * The tab is opened before the request, synchronously inside the click, and pointed at the
   * portal once the session exists: a window.open() after an await is no longer a response to
   * the click, and browsers block it as a pop-up.
   */
  const handleOpenPortal = async (consumer: ConsumerResponse) => {
    const tab = window.open('', '_blank');
    setOpeningId(consumer.id);
    try {
      const session = await createSession.mutateAsync({ consumerId: consumer.id });
      if (tab) {
        tab.opener = null;
        tab.location.href = session.url;
      }
    } catch (err) {
      tab?.close();
      showApiError(err, 'consumers.toast.sessionFailed');
    } finally {
      setOpeningId(null);
    }
  };

  const originValid = !allowedOrigin.trim() || ORIGIN_PATTERN.test(allowedOrigin.trim());

  const handleCreateEmbed = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!embedFor || !originValid) return;
    try {
      const session = await createSession.mutateAsync({
        consumerId: embedFor.id,
        data: { allowedOrigin: allowedOrigin.trim() || undefined },
      });
      setEmbedSession(session);
    } catch (err) {
      showApiError(err, 'consumers.toast.sessionFailed');
    }
  };

  const closeEmbed = () => {
    setEmbedFor(null);
    setEmbedSession(null);
    setAllowedOrigin('');
  };

  if (loading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={4} height="h-14" />
      </PageSkeleton>
    );
  }

  const integration = (
    <div className="space-y-3">
      <IntegrationSnippet
        title={t('consumers.integrate.backendTitle')}
        samples={consumerPortalSnippets({ baseUrl: apiBaseUrl(), projectId: projectId! })}
        footer={t('consumers.integrate.backendFooter')}
        docsLink="outgoing/customer-portal"
      />
      <IntegrationSnippet
        title={t('consumers.integrate.pageTitle')}
        samples={portalIframeSnippet()}
        footer={t('consumers.integrate.pageFooter')}
      />
    </div>
  );

  const newButton = (
    <PermissionGate allowed={canManageEndpoints}>
      <VerificationGate>
        <Button onClick={() => setCreating(true)}>
          <Plus className="h-4 w-4" /> {t('consumers.new')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={project.data?.name}
        title={t('consumers.title')}
        description={t('consumers.description')}
        actions={!consumers.isError && list.length > 0 ? newButton : undefined}
        guide={{
          id: 'consumers',
          docsLink: 'outgoing/customer-portal',
          // An empty page shows the code under its empty state, where nobody can collapse it away.
          children: list.length > 0 ? integration : undefined,
        }}
      />

      {consumers.isError || project.isError ? (
        <ErrorState
          error={project.error ?? consumers.error}
          fallbackKey="consumers.toast.loadFailed"
          onRetry={() => { project.refetch(); consumers.refetch(); }}
        />
      ) : list.length === 0 ? (
        <>
          <EmptyState
            icon={Users}
            title={t('consumers.empty.title')}
            description={t('consumers.empty.description')}
            action={newButton}
            docsLink="outgoing/customer-portal"
          />
          <section aria-labelledby="consumers-preview" className="mt-6">
            <h2 id="consumers-preview" className="mb-3 text-sm font-semibold">{t('consumers.preview.heading')}</h2>
            <PortalPreview />
          </section>
          <section aria-labelledby="consumers-integrate" className="mt-6">
            <h2 id="consumers-integrate" className="mb-3 text-sm font-semibold">{t('consumers.integrate.heading')}</h2>
            {integration}
          </section>
        </>
      ) : (
        <>
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('consumers.name')}</TableHead>
                  <TableHead>{t('consumers.externalId')}</TableHead>
                  <TableHead>{t('consumers.endpoints')}</TableHead>
                  <TableHead>{t('consumers.created')}</TableHead>
                  <TableHead className="w-[220px] text-right">
                    <span className="sr-only">{t('common.actions')}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.map((consumer) => (
                  <TableRow key={consumer.id}>
                    <TableCell className="font-medium">{consumer.name}</TableCell>
                    <TableCell className="font-mono text-[13px]">{consumer.externalId}</TableCell>
                    <TableCell>
                      <Button variant="link" className="h-auto p-0 font-mono text-[13px]" onClick={() => setEndpointsOf(consumer)}>
                        {t('consumers.endpointCount', { count: consumer.endpointCount })}
                      </Button>
                    </TableCell>
                    <TableCell className="font-mono text-[11px] text-muted-foreground">{formatDate(consumer.createdAt)}</TableCell>
                    <TableCell>
                      {canManageEndpoints && (
                        <div className="flex items-center justify-end gap-1">
                          <Button
                            variant="outline" size="sm" onClick={() => handleOpenPortal(consumer)}
                            disabled={openingId === consumer.id}
                          >
                            {openingId === consumer.id
                              ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
                              : <ExternalLink className="h-3.5 w-3.5" />}
                            {t('consumers.openPortal')}
                          </Button>
                          <Button
                            variant="ghost" size="icon-sm" onClick={() => setEmbedFor(consumer)}
                            title={t('consumers.embed.button')} aria-label={t('consumers.embed.button')}
                          >
                            <Code2 className="h-3.5 w-3.5" />
                          </Button>
                          <Button
                            variant="ghost" size="icon-sm" onClick={() => setDeleteTarget(consumer)}
                            title={t('common.delete')} aria-label={t('common.delete')}
                            className="text-muted-foreground hover:text-halt"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
          {consumers.data && (
            <TablePagination
              page={page}
              pageSize={pageSize}
              totalElements={consumers.data.totalElements}
              totalPages={consumers.data.totalPages}
              onPageChange={setPage}
              onPageSizeChange={(size) => { setPageSize(size); setPage(0); }}
            />
          )}
        </>
      )}

      <Dialog open={creating} onOpenChange={setCreating}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('consumers.createDialog.title')}</DialogTitle>
            <DialogDescription>{t('consumers.createDialog.description')}</DialogDescription>
          </DialogHeader>
          <form onSubmit={handleCreate}>
            <div className="space-y-4 py-2">
              <div className="space-y-2">
                <Label htmlFor="consumer-external-id">{t('consumers.externalId')}</Label>
                <Input
                  id="consumer-external-id" className="font-mono text-sm" required autoFocus
                  placeholder="user_123"
                  value={externalId} onChange={(e) => setExternalId(e.target.value)}
                  disabled={createConsumer.isPending}
                />
                <p className="text-xs text-muted-foreground">{t('consumers.createDialog.externalIdHint')}</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="consumer-name">{t('consumers.name')}</Label>
                <Input
                  id="consumer-name" placeholder={t('consumers.createDialog.namePlaceholder')}
                  value={name} onChange={(e) => setName(e.target.value)}
                  disabled={createConsumer.isPending}
                />
              </div>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setCreating(false)} disabled={createConsumer.isPending}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" disabled={createConsumer.isPending || !externalId.trim()}>
                {createConsumer.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                {t('common.create')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteTarget}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t('consumers.deleteDialog.title', { name: deleteTarget?.name ?? '' })}
        description={t('consumers.deleteDialog.description', { count: deleteTarget?.endpointCount ?? 0 })}
        onConfirm={handleDelete}
        loading={deleteConsumer.isPending}
        confirmLabel={t('common.delete')}
        destructive
      />

      <Sheet open={!!endpointsOf} onOpenChange={(open) => !open && setEndpointsOf(null)}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-lg">
          <SheetHeader>
            <SheetTitle>{t('consumers.endpointsSheet.title', { name: endpointsOf?.name ?? '' })}</SheetTitle>
            <SheetDescription>{t('consumers.endpointsSheet.description')}</SheetDescription>
          </SheetHeader>
          <div className="mt-4">
            {consumerEndpoints.isLoading ? (
              <SkeletonRows count={2} height="h-12" />
            ) : consumerEndpoints.isError ? (
              <ErrorState error={consumerEndpoints.error} fallbackKey="consumers.toast.loadFailed" onRetry={() => consumerEndpoints.refetch()} />
            ) : (consumerEndpoints.data ?? []).length === 0 ? (
              <EmptyState icon={Webhook} title={t('consumers.endpointsSheet.empty')} />
            ) : (
              <ul className="space-y-2">
                {(consumerEndpoints.data ?? []).map((endpoint) => (
                  <li key={endpoint.id} className="flex items-center justify-between gap-3 rounded-md border border-rail p-3">
                    <div className="min-w-0">
                      <div className="truncate font-mono text-[13px]" title={endpoint.url}>{endpoint.url}</div>
                      {endpoint.description && (
                        <div className="truncate text-xs text-muted-foreground">{endpoint.description}</div>
                      )}
                    </div>
                    <EnabledBadge enabled={endpoint.enabled} />
                  </li>
                ))}
              </ul>
            )}
          </div>
        </SheetContent>
      </Sheet>

      <Dialog open={!!embedFor} onOpenChange={(open) => !open && closeEmbed()}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t('consumers.embed.title', { name: embedFor?.name ?? '' })}</DialogTitle>
            <DialogDescription>{t('consumers.embed.description')}</DialogDescription>
          </DialogHeader>
          {!embedSession ? (
            <form onSubmit={handleCreateEmbed}>
              <div className="space-y-2 py-2">
                <Label htmlFor="embed-origin">{t('consumers.embed.allowedOrigin')}</Label>
                <Input
                  id="embed-origin" className="font-mono text-sm" placeholder="https://app.example.com"
                  value={allowedOrigin} onChange={(e) => setAllowedOrigin(e.target.value)}
                  aria-invalid={!originValid}
                  disabled={createSession.isPending}
                />
                <p className={originValid ? 'text-xs text-muted-foreground' : 'text-xs text-halt'}>
                  {originValid ? t('consumers.embed.allowedOriginHint') : t('consumers.embed.allowedOriginInvalid')}
                </p>
              </div>
              <DialogFooter>
                <Button type="button" variant="outline" onClick={closeEmbed} disabled={createSession.isPending}>
                  {t('common.cancel')}
                </Button>
                <Button type="submit" disabled={createSession.isPending || !originValid}>
                  {createSession.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                  {t('consumers.embed.generate')}
                </Button>
              </DialogFooter>
            </form>
          ) : (
            <div className="space-y-3">
              <IntegrationSnippet title={t('consumers.embed.snippet')} samples={portalIframeSnippet(embedSession.url)} />
              {embedFor && (
                <IntegrationSnippet
                  title={t('consumers.embed.backendTitle')}
                  samples={consumerPortalSnippets({ baseUrl: apiBaseUrl(), projectId: projectId!, consumerId: embedFor.id })}
                  docsLink="outgoing/customer-portal"
                />
              )}
              <p className="text-xs text-muted-foreground">
                {t('consumers.embed.expires', { date: formatDateTime(embedSession.expiresAt) })}{' '}
                <a
                  href={docsUrl(i18n.language, 'outgoing/customer-portal')}
                  className="underline underline-offset-2 hover:text-foreground"
                >
                  {t('consumers.embed.docs')}
                </a>
              </p>
              <DialogFooter>
                <Button onClick={closeEmbed}>{t('common.close')}</Button>
              </DialogFooter>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
