import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowDownToLine, AlertTriangle, Copy, Loader2, Pencil, Plus, Trash2,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showCriticalSuccess } from '../lib/toast';
import { formatRelativeTime } from '../lib/date';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import {
  useProject, useIncomingSources, useCreateIncomingSource, useUpdateIncomingSource,
  useDeleteIncomingSource,
} from '../api/queries';
import type {
  IncomingSourceResponse, IncomingSourceRequest, ProviderType, VerificationMode,
} from '../types/api.types';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Card } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { Select } from '../components/ui/select';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import ConfirmDialog from '../components/ConfirmDialog';

/**
 * Incoming sources — the same shape as Connections, one direction over.
 *
 * A source is a third-party provider a customer has connected, together with
 * what Railhook needs to prove a webhook genuinely came from it. Its
 * destinations live on its own page, the way subscriptions live on a
 * connection: open a source to see where its incoming events are forwarded.
 */

const PROVIDER_TYPES: ProviderType[] = ['GENERIC', 'GITHUB', 'GITLAB', 'STRIPE', 'SHOPIFY', 'SLACK', 'TWILIO'];
const VERIFICATION_MODES: VerificationMode[] = ['NONE', 'HMAC_GENERIC', 'PROVIDER'];

/**
 * The providers WebhookVerifierFactory actually ships a verifier for — which is now every
 * provider type except GENERIC.
 *
 * GENERIC is the label for a provider Railhook has no preset for, so PROVIDER mode with it is
 * refused by the API: HMAC_GENERIC, with that provider's own header and prefix, is what verifies
 * those. It used to be worse than a refusal — the source saved, and then threw at ingress once
 * the provider was already sending. Narrowing the list here means the choice that fails cannot
 * be made; the server check stays the authority.
 */
const VERIFIABLE_PROVIDERS: ProviderType[] = ['STRIPE', 'GITHUB', 'GITLAB', 'SLACK', 'SHOPIFY', 'TWILIO'];

export default function IncomingSourcesPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const { canManageIncomingSources } = usePermissions();

  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  const { data: project, isLoading: projectLoading } = useProject(projectId);
  const {
    data: pageInfo, isLoading: sourcesLoading, isError, error, refetch,
  } = useIncomingSources(projectId, currentPage, pageSize);

  const createSource = useCreateIncomingSource(projectId!);
  const updateSource = useUpdateIncomingSource(projectId!);
  const deleteSource = useDeleteIncomingSource(projectId!);

  const sources = pageInfo?.content ?? [];
  const loading = projectLoading || sourcesLoading;

  const [showDialog, setShowDialog] = useState(false);
  const [editSource, setEditSource] = useState<IncomingSourceResponse | null>(null);
  const [formName, setFormName] = useState('');
  const [formSlug, setFormSlug] = useState('');
  const [formProvider, setFormProvider] = useState<ProviderType>('GENERIC');
  const [formVerification, setFormVerification] = useState<VerificationMode>('NONE');
  const [formHmacSecret, setFormHmacSecret] = useState('');
  const [formHmacHeader, setFormHmacHeader] = useState('');
  const [formHmacPrefix, setFormHmacPrefix] = useState('');
  const [formRateLimit, setFormRateLimit] = useState('');
  const [deleteId, setDeleteId] = useState<string | null>(null);

  const saving = createSource.isPending || updateSource.isPending;

  const openCreate = () => {
    setEditSource(null);
    setFormName('');
    setFormSlug('');
    setFormProvider('GENERIC');
    setFormVerification('NONE');
    setFormHmacSecret('');
    setFormHmacHeader('');
    setFormHmacPrefix('');
    setFormRateLimit('');
    setShowDialog(true);
  };

  const openEdit = (source: IncomingSourceResponse) => {
    setEditSource(source);
    setFormName(source.name);
    setFormSlug(source.slug);
    setFormProvider(source.providerType);
    setFormVerification(source.verificationMode);
    setFormHmacSecret('');
    setFormHmacHeader(source.hmacHeaderName || '');
    setFormHmacPrefix(source.hmacSignaturePrefix || '');
    setFormRateLimit(source.rateLimitPerSecond?.toString() || '');
    setShowDialog(true);
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    const data: IncomingSourceRequest = {
      name: formName,
      slug: formSlug || undefined,
      providerType: formProvider,
      verificationMode: formVerification,
      hmacSecret: formHmacSecret || undefined,
      hmacHeaderName: formHmacHeader || undefined,
      hmacSignaturePrefix: formHmacPrefix || undefined,
      rateLimitPerSecond: formRateLimit ? parseInt(formRateLimit) : null,
    };

    try {
      if (editSource) {
        await updateSource.mutateAsync({ id: editSource.id, data });
        showSuccess(t('incomingSources.toast.updated'));
      } else {
        await createSource.mutateAsync(data);
        showSuccess(t('incomingSources.toast.created'));
      }
      setShowDialog(false);
    } catch (err) {
      showApiError(err, editSource ? 'incomingSources.toast.updateFailed' : 'incomingSources.toast.createFailed');
    }
  };

  const handleDelete = async () => {
    if (!deleteId) return;
    try {
      await deleteSource.mutateAsync(deleteId);
      showCriticalSuccess(t('incomingSources.toast.deleted'));
      setDeleteId(null);
    } catch (err) {
      showApiError(err, 'incomingSources.toast.deleteFailed');
    }
  };

  const copyIngressUrl = async (url: string) => {
    await navigator.clipboard.writeText(url);
    showSuccess(t('incomingSources.toast.urlCopied'));
  };

  const openSource = (source: IncomingSourceResponse) =>
    navigate(`/admin/projects/${projectId}/incoming-sources/${source.id}`);

  if (loading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={4} height="h-16" />
      </PageSkeleton>
    );
  }

  const newSourceButton = (
    <PermissionGate allowed={canManageIncomingSources}>
      <VerificationGate>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> {t('incomingSources.create')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={project?.name}
        title={t('incomingSources.title')}
        description={t('incomingSources.descriptionV2', 'Providers whose webhooks arrive here, and how each one is proven genuine before it is forwarded.')}
        actions={!isError && sources.length > 0 ? newSourceButton : undefined}
      />

      {isError ? (
        <ErrorState error={error} fallbackKey="incomingSources.toast.loadFailed" onRetry={() => refetch()} />
      ) : sources.length === 0 ? (
        <EmptyState
          icon={ArrowDownToLine}
          title={t('incomingSources.noSources')}
          description={t('incomingSources.noSourcesDesc')}
          action={newSourceButton}
          docsLink="/docs#incoming-webhooks"
        />
      ) : (
        <>
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('incomingSources.columnSource', 'Source')}</TableHead>
                  <TableHead>{t('incomingSources.ingressUrl')}</TableHead>
                  <TableHead>{t('incomingSources.verification')}</TableHead>
                  <TableHead>{t('endpoints.status')}</TableHead>
                  <TableHead>{t('subscriptions.created')}</TableHead>
                  <TableHead className="w-[100px] text-right">
                    <span className="sr-only">{t('common.actions')}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sources.map((source) => (
                  <TableRow key={source.id}>
                    <TableCell>
                      <button
                        type="button"
                        onClick={() => openSource(source)}
                        className="text-left text-[13px] font-medium hover:text-primary hover:underline"
                      >
                        {source.name}
                      </button>
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-[11px] text-muted-foreground">{source.slug}</span>
                        <Badge variant="outline" className="font-mono text-[10px]">{source.providerType}</Badge>
                      </div>
                    </TableCell>
                    <TableCell className="max-w-[280px]">
                      <div className="flex items-center gap-1">
                        <code className="truncate font-mono text-[12px] text-muted-foreground" title={source.ingressUrl}>
                          {source.ingressUrl}
                        </code>
                        <Button
                          variant="ghost" size="icon-sm"
                          onClick={() => copyIngressUrl(source.ingressUrl)}
                          title={t('incomingSources.howToSend.copy')}
                          aria-label={t('incomingSources.howToSend.copy')}
                        >
                          <Copy className="h-3 w-3" />
                        </Button>
                      </div>
                    </TableCell>
                    <TableCell>
                      <StatusBadge
                        kind={source.hmacSecretConfigured ? 'ok' : 'retry'}
                        label={source.hmacSecretConfigured
                          ? t('incomingSources.hmacConfigured')
                          : t('incomingSources.hmacNotConfigured')}
                      />
                    </TableCell>
                    <TableCell>
                      <StatusBadge
                        kind={source.status === 'ACTIVE' ? 'ok' : 'idle'}
                        label={source.status === 'ACTIVE' ? t('incomingSources.active') : t('incomingSources.disabled')}
                      />
                    </TableCell>
                    <TableCell>
                      <span className="font-mono text-[11px] text-muted-foreground">
                        {formatRelativeTime(source.createdAt)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-1">
                        {canManageIncomingSources && (
                          <>
                            <Button
                              variant="ghost" size="icon-sm" onClick={() => openEdit(source)}
                              title={t('common.edit')} aria-label={t('common.edit')}
                            >
                              <Pencil className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              variant="ghost" size="icon-sm" onClick={() => setDeleteId(source.id)}
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

      <Dialog open={showDialog} onOpenChange={setShowDialog}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {editSource ? t('incomingSources.editDialog.title') : t('incomingSources.createDialog.title')}
            </DialogTitle>
            <DialogDescription>
              {editSource ? t('incomingSources.editDialog.description') : t('incomingSources.createDialog.description')}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleSave}>
            <div className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="src-name">{t('incomingSources.createDialog.name')}</Label>
                <Input
                  id="src-name" placeholder={t('incomingSources.createDialog.namePlaceholder')}
                  value={formName} onChange={(e) => setFormName(e.target.value)}
                  required disabled={saving} autoFocus
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="src-slug">{t('incomingSources.createDialog.slug')}</Label>
                <Input
                  id="src-slug" className="font-mono text-sm"
                  placeholder={t('incomingSources.createDialog.slugPlaceholder')}
                  value={formSlug} onChange={(e) => setFormSlug(e.target.value)} disabled={saving}
                />
                <p className="text-xs text-muted-foreground">{t('incomingSources.createDialog.slugHint')}</p>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="src-provider">{t('incomingSources.createDialog.provider')}</Label>
                  <Select
                    id="src-provider" value={formProvider}
                    onChange={(e) => setFormProvider(e.target.value as ProviderType)} disabled={saving}
                  >
                    {(formVerification === 'PROVIDER' ? VERIFIABLE_PROVIDERS : PROVIDER_TYPES)
                      .map((p) => <option key={p} value={p}>{p}</option>)}
                  </Select>
                  {formVerification === 'PROVIDER' && (
                    <p className="text-xs text-muted-foreground">
                      {t('incomingSources.createDialog.providerVerifiedHint')}
                    </p>
                  )}
                </div>
                <div className="space-y-2">
                  <Label htmlFor="src-verification">{t('incomingSources.createDialog.verificationMode')}</Label>
                  <Select
                    id="src-verification" value={formVerification}
                    onChange={(e) => {
                      const next = e.target.value as VerificationMode;
                      setFormVerification(next);
                      if (next === 'PROVIDER' && !VERIFIABLE_PROVIDERS.includes(formProvider)) {
                        setFormProvider('STRIPE');
                      }
                    }}
                    disabled={saving}
                  >
                    {VERIFICATION_MODES.map((v) => <option key={v} value={v}>{v}</option>)}
                  </Select>
                </div>
              </div>

              {formVerification === 'NONE' && (
                <div className="flex items-start gap-2.5 rounded-lg border border-retry/30 bg-retry-soft p-3">
                  <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-retry" aria-hidden />
                  <div>
                    <p className="text-sm font-medium text-retry">{t('incomingSources.security.noVerificationTitle')}</p>
                    <p className="mt-0.5 text-xs text-retry">{t('incomingSources.security.noVerificationDesc')}</p>
                  </div>
                </div>
              )}

              {formVerification === 'HMAC_GENERIC' && (
                <>
                  <div className="space-y-2">
                    <Label htmlFor="src-hmac-secret">{t('incomingSources.createDialog.hmacSecret')}</Label>
                    <Input
                      id="src-hmac-secret" type="password" className="font-mono text-sm"
                      placeholder={t('incomingSources.createDialog.hmacSecretPlaceholder')}
                      value={formHmacSecret} onChange={(e) => setFormHmacSecret(e.target.value)} disabled={saving}
                    />
                    <p className="text-xs text-muted-foreground">{t('incomingSources.createDialog.hmacSecretHint')}</p>
                  </div>
                  <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-2">
                      <Label htmlFor="src-hmac-header">{t('incomingSources.createDialog.hmacHeaderName')}</Label>
                      <Input
                        id="src-hmac-header" className="font-mono text-sm"
                        placeholder={t('incomingSources.createDialog.hmacHeaderPlaceholder')}
                        value={formHmacHeader} onChange={(e) => setFormHmacHeader(e.target.value)} disabled={saving}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="src-hmac-prefix">{t('incomingSources.createDialog.hmacSignaturePrefix')}</Label>
                      <Input
                        id="src-hmac-prefix" className="font-mono text-sm"
                        placeholder={t('incomingSources.createDialog.hmacPrefixPlaceholder')}
                        value={formHmacPrefix} onChange={(e) => setFormHmacPrefix(e.target.value)} disabled={saving}
                      />
                    </div>
                  </div>
                </>
              )}

              <div className="space-y-2">
                <Label htmlFor="src-rate-limit">{t('incomingSources.createDialog.rateLimit')}</Label>
                <Input
                  id="src-rate-limit" type="number" min="1" max="10000" className="font-mono text-sm"
                  placeholder={t('incomingSources.createDialog.rateLimitPlaceholder')}
                  value={formRateLimit} onChange={(e) => setFormRateLimit(e.target.value)} disabled={saving}
                />
              </div>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setShowDialog(false)} disabled={saving}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" disabled={saving}>
                {saving && <Loader2 className="h-4 w-4 animate-spin" />}
                {saving ? t('common.saving') : t('common.save')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteId}
        onOpenChange={(open) => !open && setDeleteId(null)}
        title={t('incomingSources.deleteDialog.title')}
        description={t('incomingSources.deleteDialog.description')}
        onConfirm={handleDelete}
        loading={deleteSource.isPending}
      />
    </div>
  );
}
