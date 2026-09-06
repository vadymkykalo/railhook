import { useState, useEffect, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import { Plus, Key, Loader2, Trash2, Copy, Check, AlertTriangle, RefreshCw } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { formatDateTimeShort, formatRelativeTime } from '../lib/date';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import DangerConfirmDialog from '../components/DangerConfirmDialog';
import ConfirmDialog from '../components/ConfirmDialog';
import { apiKeysApi, ApiKeyResponse, ApiKeyScope } from '../api/apiKeys.api';
import { projectsApi } from '../api/projects.api';
import type { ProjectResponse, PageResponse } from '../types/api.types';
import { cn } from '../lib/utils';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import { Card, CardContent } from '../components/ui/card';
import { TablePagination } from '../components/ui/table-pagination';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/ui/dialog';
import { usePermissions } from '../auth/usePermissions';

const SCOPES: ApiKeyScope[] = ['READ_WRITE', 'READ_ONLY'];
const PAGE_SIZE = 20;

/** A key is identified by its prefix and nothing else once it has been issued. */
function KeyFingerprint({ prefix }: { prefix: string }) {
  return (
    <span className="font-mono text-[13px] text-muted-foreground">
      {prefix}
      <span aria-hidden>{'•'.repeat(12)}</span>
    </span>
  );
}

export default function ApiKeysPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageApiKeys } = usePermissions();

  const [project, setProject] = useState<ProjectResponse | null>(null);
  const [apiKeys, setApiKeys] = useState<ApiKeyResponse[]>([]);
  const [pageInfo, setPageInfo] = useState<PageResponse<ApiKeyResponse> | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize, setPageSize] = useState(PAGE_SIZE);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);

  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [name, setName] = useState('');
  const [scope, setScope] = useState<ApiKeyScope>('READ_WRITE');
  const [expiresIn, setExpiresIn] = useState('');
  const [creating, setCreating] = useState(false);

  const [revoking, setRevoking] = useState<ApiKeyResponse | null>(null);
  const [revokePending, setRevokePending] = useState(false);
  const [rotating, setRotating] = useState<ApiKeyResponse | null>(null);
  const [rotateGraceHours, setRotateGraceHours] = useState('24');
  const [rotatePending, setRotatePending] = useState(false);
  const [newApiKey, setNewApiKey] = useState<ApiKeyResponse | null>(null);
  const [copied, setCopied] = useState(false);

  const loadData = useCallback(async () => {
    if (!projectId) return;
    try {
      setLoading(true);
      const [projectData, apiKeysData] = await Promise.all([
        projectsApi.get(projectId),
        apiKeysApi.listPaged(projectId, currentPage, pageSize),
      ]);
      setProject(projectData);
      setApiKeys(apiKeysData.content);
      setPageInfo(apiKeysData);
      setLoadError(null);
    } catch (err: any) {
      setLoadError(err);
    } finally {
      setLoading(false);
    }
  }, [projectId, currentPage, pageSize]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!projectId) return;
    setCreating(true);
    try {
      let expiresAt: string | undefined;
      if (expiresIn) {
        const d = new Date();
        d.setDate(d.getDate() + parseInt(expiresIn));
        expiresAt = d.toISOString();
      }
      const response = await apiKeysApi.create(projectId, { name, scope, expiresAt });
      setShowCreateDialog(false);
      setName('');
      setScope('READ_WRITE');
      setExpiresIn('');
      setCopied(false);
      setNewApiKey(response);
      loadData();
    } catch (err: any) {
      showApiError(err, 'apiKeys.toast.createFailed');
    } finally {
      setCreating(false);
    }
  };

  const handleRevoke = async () => {
    if (!revoking || !projectId) return;
    setRevokePending(true);
    try {
      await apiKeysApi.revoke(projectId, revoking.id);
      showSuccess(t('apiKeys.toast.revoked'));
      setRevoking(null);
      loadData();
    } catch (err: any) {
      showApiError(err, 'apiKeys.toast.revokeFailed');
    } finally {
      setRevokePending(false);
    }
  };

  const handleRotate = async () => {
    if (!rotating || !projectId) return;
    setRotatePending(true);
    try {
      const replacement = await apiKeysApi.rotate(projectId, rotating.id, {
        gracePeriodHours: parseInt(rotateGraceHours, 10),
      });
      setRotating(null);
      setCopied(false);
      // Straight into the same one-and-only-sighting dialog the create flow uses: a rotation
      // produces a real key that is shown exactly once, and inventing a second way to show it
      // would be two places to get "you cannot see this again" wrong.
      setNewApiKey(replacement);
      loadData();
    } catch (err: any) {
      showApiError(err, 'apiKeys.toast.rotateFailed');
    } finally {
      setRotatePending(false);
    }
  };

  const handleCopyKey = () => {
    if (!newApiKey?.key) return;
    navigator.clipboard.writeText(newApiKey.key);
    setCopied(true);
    showSuccess(t('apiKeys.toast.copied'));
  };

  if (loading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={3} height="h-24" />
      </PageSkeleton>
    );
  }

  if (loadError || !project) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={loadError} fallbackKey="apiKeys.toast.loadFailed" onRetry={loadData} />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={project.name}
        title={t('apiKeys.title')}
        description={t('apiKeys.subtitlePlain')}
        actions={
          <PermissionGate allowed={canManageApiKeys}>
            <Button onClick={() => setShowCreateDialog(true)}>
              <Plus className="h-4 w-4" aria-hidden /> {t('apiKeys.createKey')}
            </Button>
          </PermissionGate>
        }
      />

      {apiKeys.length === 0 ? (
        <EmptyState
          icon={Key}
          title={t('apiKeys.noKeys')}
          description={canManageApiKeys ? t('apiKeys.noKeysDesc') : t('apiKeys.noKeysDescViewer')}
          action={canManageApiKeys ? (
            <Button onClick={() => setShowCreateDialog(true)}>
              <Plus className="h-4 w-4" aria-hidden /> {t('apiKeys.createKey')}
            </Button>
          ) : undefined}
        />
      ) : (
        <div className="animate-fade-in space-y-3">
          {apiKeys.map((apiKey) => {
            const expired = !!apiKey.expiresAt && new Date(apiKey.expiresAt) < new Date();
            // A key with a rotated-at is not merely expiring, it is being handed over: its
            // successor is already live and this one stops working when the window closes.
            const retiring = !!apiKey.rotatedAt && !expired;
            return (
              <Card key={apiKey.id}>
                <CardContent className="flex flex-wrap items-start justify-between gap-4 p-4 lg:p-5">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="text-sm font-medium">{apiKey.name}</p>
                      <StatusBadge
                        kind={expired ? 'halt' : retiring ? 'retry' : 'ok'}
                        label={t(expired ? 'apiKeys.expired' : retiring ? 'apiKeys.retiring' : 'apiKeys.activeKey')}
                      />
                      <span className="rounded-md border border-rail px-2 py-0.5 font-mono text-[11px] text-muted-foreground">
                        {t(apiKey.scope === 'READ_ONLY' ? 'apiKeys.scopeReadOnly' : 'apiKeys.scopeReadWrite')}
                      </span>
                    </div>
                    <div className="mt-1.5">
                      <KeyFingerprint prefix={apiKey.keyPrefix} />
                    </div>
                    <dl className="mt-2.5 flex flex-wrap gap-x-5 gap-y-1 text-[11px]">
                      <div className="flex gap-1.5">
                        <dt className="mono-label">{t('apiKeys.created')}</dt>
                        <dd className="text-muted-foreground">{formatDateTimeShort(apiKey.createdAt)}</dd>
                      </div>
                      <div className="flex gap-1.5">
                        <dt className="mono-label">{t('apiKeys.lastUsed')}</dt>
                        <dd className="text-muted-foreground">
                          {apiKey.lastUsedAt ? formatRelativeTime(apiKey.lastUsedAt) : t('apiKeys.never')}
                        </dd>
                      </div>
                      <div className="flex gap-1.5">
                        <dt className="mono-label">{t(retiring ? 'apiKeys.stopsWorking' : 'apiKeys.expires')}</dt>
                        <dd className={cn('text-muted-foreground', expired && 'text-halt', retiring && 'text-retry')}>
                          {apiKey.expiresAt ? formatDateTimeShort(apiKey.expiresAt) : t('apiKeys.createDialog.noExpiration')}
                        </dd>
                      </div>
                    </dl>
                    {retiring && (
                      <p className="mt-2 text-xs text-retry">{t('apiKeys.retiringHint')}</p>
                    )}
                  </div>
                  {canManageApiKeys && (
                    <div className="flex flex-shrink-0 items-center gap-1">
                    {!apiKey.rotatedAt && !expired && (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      onClick={() => { setRotateGraceHours('24'); setRotating(apiKey); }}
                      title={t('apiKeys.rotate')}
                      aria-label={t('apiKeys.rotateNamed', { name: apiKey.name })}
                      className="flex-shrink-0 text-muted-foreground hover:text-primary"
                    >
                      <RefreshCw className="h-3.5 w-3.5" />
                    </Button>
                    )}
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      onClick={() => setRevoking(apiKey)}
                      title={t('apiKeys.revoke')}
                      aria-label={t('apiKeys.revokeNamed', { name: apiKey.name })}
                      className="flex-shrink-0 text-muted-foreground hover:text-halt"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                    </div>
                  )}
                </CardContent>
              </Card>
            );
          })}

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
        </div>
      )}

      {/* Create */}
      <Dialog open={showCreateDialog} onOpenChange={setShowCreateDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('apiKeys.createDialog.title')}</DialogTitle>
            <DialogDescription>{t('apiKeys.createDialog.description')}</DialogDescription>
          </DialogHeader>
          <form onSubmit={handleCreate}>
            <div className="space-y-5 py-4">
              <div className="space-y-2">
                <Label htmlFor="key-name">{t('apiKeys.createDialog.name')}</Label>
                <Input
                  id="key-name"
                  placeholder={t('apiKeys.createDialog.namePlaceholder')}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  disabled={creating}
                  autoFocus
                />
                <p className="text-xs text-muted-foreground">{t('apiKeys.createDialog.nameHint')}</p>
              </div>

              <div className="space-y-2">
                <span className="text-sm font-medium leading-none">{t('apiKeys.createDialog.scope')}</span>
                <div role="radiogroup" aria-label={t('apiKeys.createDialog.scope')} className="grid gap-2.5 sm:grid-cols-2">
                  {SCOPES.map((s) => (
                    <button
                      key={s}
                      type="button"
                      role="radio"
                      aria-checked={scope === s}
                      disabled={creating}
                      onClick={() => setScope(s)}
                      className={cn(
                        'rounded-lg border p-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
                        scope === s ? 'border-primary bg-accent/40' : 'border-rail bg-card hover:border-primary/40'
                      )}
                    >
                      <span className="flex items-center gap-2 text-sm font-medium">
                        {t(s === 'READ_ONLY' ? 'apiKeys.scopeReadOnly' : 'apiKeys.scopeReadWrite')}
                        {scope === s && <Check className="ml-auto h-4 w-4 text-primary" aria-hidden />}
                      </span>
                      <span className="mt-1 block text-xs leading-snug text-muted-foreground">
                        {t(s === 'READ_ONLY' ? 'apiKeys.createDialog.scopeReadOnlyHint' : 'apiKeys.createDialog.scopeReadWriteHint')}
                      </span>
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="key-expiry">{t('apiKeys.createDialog.expiration')}</Label>
                <Select id="key-expiry" value={expiresIn} onChange={(e) => setExpiresIn(e.target.value)} disabled={creating}>
                  <option value="">{t('apiKeys.createDialog.noExpiration')}</option>
                  <option value="7">{t('apiKeys.createDialog.expires7d')}</option>
                  <option value="30">{t('apiKeys.createDialog.expires30d')}</option>
                  <option value="90">{t('apiKeys.createDialog.expires90d')}</option>
                  <option value="365">{t('apiKeys.createDialog.expires1y')}</option>
                </Select>
                <p className="text-xs text-muted-foreground">{t('apiKeys.createDialog.expirationHint')}</p>
              </div>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setShowCreateDialog(false)} disabled={creating}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" disabled={creating}>
                {creating && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
                {creating ? t('apiKeys.createDialog.submitting') : t('apiKeys.createDialog.submit')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Rotate: the create-then-revoke race, done by the server instead of by hand. */}
      <ConfirmDialog
        open={!!rotating}
        onOpenChange={(open) => !open && setRotating(null)}
        title={t('apiKeys.rotateDialog.title', { name: rotating?.name ?? '' })}
        description={t('apiKeys.rotateDialog.description')}
        confirmLabel={t('apiKeys.rotate')}
        destructive={false}
        loading={rotatePending}
        onConfirm={handleRotate}
      >
        <div className="space-y-2">
          <Label htmlFor="rotate-grace">{t('apiKeys.rotateDialog.grace')}</Label>
          <Select
            id="rotate-grace"
            value={rotateGraceHours}
            onChange={(e) => setRotateGraceHours(e.target.value)}
            disabled={rotatePending}
          >
            <option value="0">{t('apiKeys.rotateDialog.grace0')}</option>
            <option value="1">{t('apiKeys.rotateDialog.grace1h')}</option>
            <option value="24">{t('apiKeys.rotateDialog.grace24h')}</option>
            <option value="72">{t('apiKeys.rotateDialog.grace72h')}</option>
            <option value="168">{t('apiKeys.rotateDialog.grace168h')}</option>
          </Select>
          <p className="text-xs text-muted-foreground">
            {t(rotateGraceHours === '0' ? 'apiKeys.rotateDialog.grace0Hint' : 'apiKeys.rotateDialog.graceHint')}
          </p>
        </div>
      </ConfirmDialog>

      {/* Revoke */}
      <DangerConfirmDialog
        open={!!revoking}
        onOpenChange={(open) => !open && setRevoking(null)}
        title={t('apiKeys.revokeDialog.title')}
        description={t('apiKeys.revokeDialog.description')}
        confirmName={revoking?.name ?? ''}
        impact={[
          t('apiKeys.revokeDialog.impactImmediate'),
          t('apiKeys.revokeDialog.impactCallers'),
          t('apiKeys.revokeDialog.impactPermanent'),
        ]}
        onConfirm={handleRevoke}
        loading={revokePending}
        confirmLabel={t('apiKeys.revoke')}
      />

      {/* The one and only sighting of the secret. */}
      <Dialog open={!!newApiKey} onOpenChange={(open) => { if (!open) { setNewApiKey(null); setCopied(false); } }}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{t('apiKeys.keyDialog.title', { name: newApiKey?.name ?? '' })}</DialogTitle>
            <DialogDescription>{t('apiKeys.keyDialog.description')}</DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-2">
            <p className="flex items-start gap-2.5 rounded-lg border border-retry/40 bg-retry-soft p-3.5 text-sm font-medium text-retry">
              <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0" aria-hidden />
              {t('apiKeys.keyDialog.onlyChance')}
            </p>

            <div className="space-y-2">
              <Label htmlFor="new-key">{t('apiKeys.keyDialog.label')}</Label>
              <div className="rounded-lg border border-rail bg-secondary/60 p-3">
                <code id="new-key" className="block break-all font-mono text-[13px] leading-relaxed">
                  {newApiKey?.key}
                </code>
              </div>
              <Button
                type="button"
                onClick={handleCopyKey}
                variant={copied ? 'success' : 'default'}
                className="w-full"
              >
                {copied ? <Check className="h-4 w-4" aria-hidden /> : <Copy className="h-4 w-4" aria-hidden />}
                {t(copied ? 'apiKeys.keyDialog.copied' : 'apiKeys.keyDialog.copyKey')}
              </Button>
            </div>

            <div className="space-y-1.5">
              <p className="mono-label">{t('apiKeys.keyDialog.howToUse')}</p>
              <pre className="overflow-x-auto rounded-lg border border-rail bg-secondary/60 p-3 font-mono text-[11px] leading-relaxed text-muted-foreground">
{`curl -X POST https://your-domain.com/api/v1/events \\
  -H "X-API-Key: $HOOKFLOW_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"type":"user.created","data":{"userId":"123"}}'`}
              </pre>
            </div>
          </div>

          <DialogFooter>
            <Button
              variant={copied ? 'default' : 'outline'}
              onClick={() => { setNewApiKey(null); setCopied(false); }}
            >
              {t('apiKeys.keyDialog.done')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
