import { useState, useEffect, useCallback } from 'react';
import { Cable, Copy, RefreshCw, Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { formatRelativeTime } from '../lib/date';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import { tunnelsApi, TunnelSessionResponse, TunnelStatusResponse } from '../api/tunnels.api';
import { Button, buttonVariants } from '../components/ui/button';
import { Card, CardContent } from '../components/ui/card';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { usePermissions } from '../auth/usePermissions';

/** A tunnel lives only as long as a CLI stays connected, so the page is a live roster. */
export default function TunnelsPage() {
  const { t } = useTranslation();
  const { canManageEndpoints: canCloseTunnels } = usePermissions();
  const [tunnels, setTunnels] = useState<TunnelSessionResponse[]>([]);
  const [status, setStatus] = useState<TunnelStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [closeId, setCloseId] = useState<string | null>(null);
  const [closing, setClosing] = useState(false);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [tunnelsData, statusData] = await Promise.all([tunnelsApi.list(), tunnelsApi.status()]);
      setTunnels(tunnelsData);
      setStatus(statusData);
      setLoadError(null);
    } catch (err: any) {
      setLoadError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  const handleClose = async () => {
    if (!closeId) return;
    setClosing(true);
    try {
      await tunnelsApi.close(closeId);
      showSuccess(t('tunnels.toast.closed'));
      setCloseId(null);
      loadData();
    } catch (err: any) {
      showApiError(err, 'tunnels.toast.closeFailed');
    } finally {
      setClosing(false);
    }
  };

  const handleCopyUrl = (url: string) => {
    navigator.clipboard.writeText(url);
    showSuccess(t('common.copied'));
  };

  if (loading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={3} height="h-24" />
      </PageSkeleton>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={status ? t('tunnels.openCount', { count: status.activeTunnels }) : undefined}
        title={t('tunnels.title')}
        description={t('tunnels.subtitle')}
        actions={
          <Button variant="outline" onClick={loadData}>
            <RefreshCw className="h-4 w-4" aria-hidden /> {t('tunnels.refresh')}
          </Button>
        }
      />

      {status && (
        <dl className="mb-6 grid grid-cols-3 divide-x divide-rail rounded-lg border border-rail bg-card">
          {([
            ['tunnels.stats.active', status.activeTunnels],
            ['tunnels.stats.pending', status.pendingRequests],
            ['tunnels.stats.mine', status.myTunnels.length],
          ] as const).map(([key, value]) => (
            <div key={key} className="px-4 py-3">
              <dt className="mono-label">{t(key)}</dt>
              <dd className="mt-1 font-mono text-xl">{value}</dd>
            </div>
          ))}
        </dl>
      )}

      {loadError ? (
        <ErrorState error={loadError} fallbackKey="tunnels.toast.loadFailed" onRetry={loadData} />
      ) : tunnels.length === 0 ? (
        <EmptyState
          icon={Cable}
          title={t('tunnels.noTunnels')}
          description={t('tunnels.noTunnelsDesc')}
        />
      ) : (
        <div className="animate-fade-in space-y-3">
          {tunnels.map((tunnel) => (
            <Card key={tunnel.id}>
              <CardContent className="flex flex-wrap items-start justify-between gap-4 p-4 lg:p-5">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <code className="font-mono text-sm font-medium">{tunnel.publicSlug}</code>
                    <StatusBadge kind="ok" label={t('tunnels.active')} />
                  </div>
                  <div className="mt-1.5 flex items-center gap-1.5">
                    <code className="min-w-0 truncate font-mono text-[13px] text-muted-foreground">{tunnel.publicUrl}</code>
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      onClick={() => handleCopyUrl(tunnel.publicUrl)}
                      title={t('tunnels.copyUrl')}
                      aria-label={t('tunnels.copyUrl')}
                      className="h-6 w-6 flex-shrink-0 text-muted-foreground hover:text-foreground"
                    >
                      <Copy className="h-3 w-3" />
                    </Button>
                  </div>
                  <dl className="mt-2.5 flex flex-wrap gap-x-5 gap-y-1 text-[11px]">
                    <div className="flex gap-1.5">
                      <dt className="mono-label">{t('tunnels.localPort')}</dt>
                      <dd className="font-mono text-muted-foreground">localhost:{tunnel.localPort}</dd>
                    </div>
                    <div className="flex gap-1.5">
                      <dt className="mono-label">{t('tunnels.created')}</dt>
                      <dd className="text-muted-foreground">{formatRelativeTime(tunnel.createdAt)}</dd>
                    </div>
                    {tunnel.lastHeartbeat && (
                      <div className="flex gap-1.5">
                        <dt className="mono-label">{t('tunnels.lastHeartbeat')}</dt>
                        <dd className="text-muted-foreground">{formatRelativeTime(tunnel.lastHeartbeat)}</dd>
                      </div>
                    )}
                    {tunnel.clientInfo && (
                      <div className="flex gap-1.5">
                        <dt className="mono-label">{t('tunnels.client')}</dt>
                        <dd className="text-muted-foreground">{tunnel.clientInfo}</dd>
                      </div>
                    )}
                  </dl>
                </div>
                <PermissionGate allowed={canCloseTunnels} fallback="hide">
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => setCloseId(tunnel.id)}
                    title={t('tunnels.close')}
                    aria-label={t('tunnels.closeNamed', { name: tunnel.publicSlug })}
                    className="flex-shrink-0 text-muted-foreground hover:text-halt"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </PermissionGate>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <AlertDialog open={!!closeId} onOpenChange={(open) => !open && setCloseId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('tunnels.closeDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('tunnels.closeDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={closing}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleClose}
              disabled={closing}
              className={buttonVariants({ variant: 'destructive' })}
            >
              {closing ? t('tunnels.closing') : t('tunnels.close')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
