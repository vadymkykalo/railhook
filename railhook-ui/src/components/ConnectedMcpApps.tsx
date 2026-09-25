import { useState } from 'react';
import { Bot, Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useMcpGrants, useRevokeMcpGrant } from '../api/queries';
import { usePermissions } from '../auth/usePermissions';
import { formatDateTimeShort, formatRelativeTime } from '../lib/date';
import { showApiError, showSuccess } from '../lib/toast';
import { siteUrl } from '../lib/siteUrl';
import type { McpGrantResponse } from '../types/api.types';
import DangerConfirmDialog from './DangerConfirmDialog';
import { Button } from './ui/button';
import { Card, CardContent } from './ui/card';

export default function ConnectedMcpApps({ projectId }: { projectId: string }) {
  const { t } = useTranslation();
  const { canManageApiKeys } = usePermissions();
  const { data: grants = [], isLoading, isError } = useMcpGrants(projectId);
  const revoke = useRevokeMcpGrant(projectId);
  const [revoking, setRevoking] = useState<McpGrantResponse | null>(null);

  const handleRevoke = async () => {
    if (!revoking) return;
    try {
      await revoke.mutateAsync(revoking.id);
      showSuccess(t('mcpApps.revoked', { name: revoking.clientName }));
      setRevoking(null);
    } catch (error) {
      showApiError(error, 'mcpApps.revokeFailed');
    }
  };

  return (
    <section aria-labelledby="mcp-apps-heading" className="mt-10">
      <div className="mb-3">
        <h2 id="mcp-apps-heading" className="text-base font-medium tracking-tight">{t('mcpApps.title')}</h2>
        <p className="mt-1 max-w-2xl text-sm text-muted-foreground">{t('mcpApps.description')}</p>
      </div>

      {isLoading ? (
        <div className="h-20 animate-pulse border border-rail bg-card" aria-hidden />
      ) : isError ? (
        <p role="alert" className="border border-halt/25 bg-halt-soft p-4 text-sm text-halt">
          {t('mcpApps.loadFailed')}
        </p>
      ) : grants.length === 0 ? (
        <div className="border border-dashed border-rail p-5 text-sm text-muted-foreground">
          <p>{t('mcpApps.empty')}</p>
          <p className="mt-2 text-xs">
            {t('mcpApps.emptyHint')}{' '}
            <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-[12px] text-foreground">{siteUrl()}/mcp</code>
          </p>
        </div>
      ) : (
        <ul className="space-y-3" aria-label={t('mcpApps.title')}>
          {grants.map((grant) => (
            <li key={grant.id}>
              <Card>
                <CardContent className="flex flex-wrap items-start justify-between gap-4 p-4 lg:p-5">
                  <div className="flex min-w-0 flex-1 gap-3">
                    <span
                      aria-hidden
                      className="flex h-9 w-9 flex-shrink-0 items-center justify-center bg-primary/10 text-primary"
                    >
                      <Bot className="h-4 w-4" />
                    </span>
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <p className="text-sm font-medium">{grant.clientName}</p>
                        <span className="border border-rail px-2 py-0.5 font-mono text-[11px] text-muted-foreground">
                          {t(grant.scope === 'READ_ONLY' ? 'apiKeys.scopeReadOnly' : 'apiKeys.scopeReadWrite')}
                        </span>
                      </div>
                      <p className="mt-1 truncate font-mono text-[12px] text-muted-foreground">{grant.redirectHost}</p>
                      <dl className="mt-2.5 flex flex-wrap gap-x-5 gap-y-1 text-[11px]">
                        <div className="flex gap-1.5">
                          <dt className="mono-label">{t('mcpApps.connected')}</dt>
                          <dd className="text-muted-foreground">{formatDateTimeShort(grant.createdAt)}</dd>
                        </div>
                        <div className="flex gap-1.5">
                          <dt className="mono-label">{t('apiKeys.lastUsed')}</dt>
                          <dd className="text-muted-foreground">
                            {grant.lastUsedAt ? formatRelativeTime(grant.lastUsedAt) : t('apiKeys.never')}
                          </dd>
                        </div>
                        {grant.approvedByEmail && (
                          <div className="flex min-w-0 gap-1.5">
                            <dt className="mono-label">{t('mcpApps.approvedBy')}</dt>
                            <dd className="truncate text-muted-foreground">{grant.approvedByEmail}</dd>
                          </div>
                        )}
                      </dl>
                    </div>
                  </div>
                  {canManageApiKeys && (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      onClick={() => setRevoking(grant)}
                      title={t('mcpApps.revoke')}
                      aria-label={t('mcpApps.revokeNamed', { name: grant.clientName })}
                      className="flex-shrink-0 text-muted-foreground hover:text-halt"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  )}
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      )}

      <DangerConfirmDialog
        open={!!revoking}
        onOpenChange={(open) => !open && setRevoking(null)}
        title={t('mcpApps.revokeDialog.title')}
        description={t('mcpApps.revokeDialog.description')}
        confirmName={revoking?.clientName ?? ''}
        impact={[
          t('mcpApps.revokeDialog.impactImmediate'),
          t('mcpApps.revokeDialog.impactReconnect'),
        ]}
        onConfirm={handleRevoke}
        loading={revoke.isPending}
        confirmLabel={t('mcpApps.revoke')}
      />
    </section>
  );
}
