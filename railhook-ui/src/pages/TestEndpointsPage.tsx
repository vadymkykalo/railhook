import { useState, useEffect, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import { Plus, Trash2, Copy, RefreshCw, Loader2, Clock, ChevronDown, ChevronRight, Eraser, Inbox, TestTube } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { formatDateTime } from '../lib/date';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import JsonEditor from '../components/JsonEditor';
import { Workbench, WorkbenchPanel, OutputBlock, ResultPlaceholder } from '../components/Workbench';
import { testEndpointsApi, type TestEndpointResponse, type CapturedRequestResponse } from '../api/testEndpoints.api';
import { Button, buttonVariants } from '../components/ui/button';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { usePermissions } from '../auth/usePermissions';
import { cn } from '../lib/utils';
import { formatJson } from '../lib/json';

/**
 * A throwaway URL that keeps whatever is posted to it.
 *
 * Same workbench shape as the rest of Develop: what you are driving on the
 * left (which endpoint), what came back on the right (what it captured).
 */
export default function TestEndpointsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageTestEndpoints } = usePermissions();

  const [endpoints, setEndpoints] = useState<TestEndpointResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [creating, setCreating] = useState(false);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [selectedEndpoint, setSelectedEndpoint] = useState<string | null>(null);
  const [requests, setRequests] = useState<CapturedRequestResponse[]>([]);
  const [loadingRequests, setLoadingRequests] = useState(false);
  const [expandedRequest, setExpandedRequest] = useState<string | null>(null);
  const [clearing, setClearing] = useState(false);

  const loadEndpoints = useCallback(async () => {
    if (!projectId) return;
    try {
      setLoading(true);
      setEndpoints(await testEndpointsApi.list(projectId));
      setLoadError(null);
    } catch (err: any) {
      setLoadError(err);
      showApiError(err, 'testEndpoints.toast.loadFailed');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  const loadRequests = useCallback(async (endpointId: string) => {
    if (!projectId) return;
    try {
      setLoadingRequests(true);
      const data = await testEndpointsApi.getRequests(projectId, endpointId);
      setRequests(data.content);
    } catch (err: any) {
      showApiError(err, 'testEndpoints.toast.loadRequestsFailed');
    } finally {
      setLoadingRequests(false);
    }
  }, [projectId]);

  useEffect(() => {
    if (projectId) loadEndpoints();
  }, [projectId, loadEndpoints]);

  useEffect(() => {
    if (selectedEndpoint && projectId) loadRequests(selectedEndpoint);
  }, [selectedEndpoint, projectId, loadRequests]);

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    showSuccess(t('testEndpoints.toast.urlCopied'));
  };

  const handleCreate = async () => {
    if (!projectId) return;
    try {
      setCreating(true);
      const endpoint = await testEndpointsApi.create(projectId);
      setEndpoints([endpoint, ...endpoints]);
      setSelectedEndpoint(endpoint.id);
      showSuccess(t('testEndpoints.toast.created'));
      copyToClipboard(endpoint.url);
    } catch (err: any) {
      showApiError(err, 'testEndpoints.toast.createFailed');
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteId || !projectId) return;
    try {
      setDeleting(true);
      await testEndpointsApi.delete(projectId, deleteId);
      setEndpoints(endpoints.filter((e) => e.id !== deleteId));
      if (selectedEndpoint === deleteId) {
        setSelectedEndpoint(null);
        setRequests([]);
      }
      showSuccess(t('testEndpoints.toast.deleted'));
    } catch (err: any) {
      showApiError(err, 'testEndpoints.toast.deleteFailed');
    } finally {
      setDeleting(false);
      setDeleteId(null);
    }
  };

  const handleClearRequests = async () => {
    if (!selectedEndpoint || !projectId) return;
    try {
      setClearing(true);
      await testEndpointsApi.clearRequests(projectId, selectedEndpoint);
      setRequests([]);
      setEndpoints(endpoints.map((e) => (e.id === selectedEndpoint ? { ...e, requestCount: 0 } : e)));
      showSuccess(t('testEndpoints.toast.cleared'));
    } catch (err: any) {
      showApiError(err, 'testEndpoints.toast.clearFailed');
    } finally {
      setClearing(false);
    }
  };

  const timeRemaining = (expiresAt: string) => {
    const diff = new Date(expiresAt).getTime() - Date.now();
    if (diff <= 0) return t('testEndpoints.expired');
    const hours = Math.floor(diff / 3600000);
    const minutes = Math.floor((diff % 3600000) / 60000);
    return t('testEndpoints.expiresIn', { value: hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m` });
  };

  const parseHeaders = (headers?: string) => {
    if (!headers) return {};
    try { return JSON.parse(headers); } catch { return {}; }
  };

  if (loading) {
    return (
      <PageSkeleton>
        <div className="grid gap-6 lg:grid-cols-2">
          <SkeletonRows count={2} height="h-28" />
          <div className="h-64 animate-pulse rounded-xl bg-muted" />
        </div>
      </PageSkeleton>
    );
  }

  const createButton = (label: string) => (
    canManageTestEndpoints ? (
      <Button onClick={handleCreate} disabled={creating}>
        {creating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
        {label}
      </Button>
    ) : null
  );

  if (loadError) {
    return (
      <div className="p-4 lg:p-6">
        <PageHeader title={t('testEndpoints.title')} description={t('testEndpoints.subtitle')} />
        <ErrorState
          error={loadError}
          fallbackKey="testEndpoints.toast.loadFailed"
          onRetry={loadEndpoints}
          retrying={loading}
        />
      </div>
    );
  }

  if (endpoints.length === 0) {
    return (
      <div className="p-4 lg:p-6">
        <PageHeader title={t('testEndpoints.title')} description={t('testEndpoints.subtitle')} />
        <EmptyState
          icon={TestTube}
          title={t('testEndpoints.noEndpoints')}
          description={t('testEndpoints.noEndpointsHint')}
          action={createButton(t('testEndpoints.create'))}
        />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('testEndpoints.count', { count: endpoints.length })}
        title={t('testEndpoints.title')}
        description={t('testEndpoints.subtitle')}
        actions={createButton(t('testEndpoints.create'))}
      />

      <Workbench
        input={
          <WorkbenchPanel
            eyebrow={t('testEndpoints.inputEyebrow')}
            title={t('testEndpoints.endpoints')}
            bodyClassName="space-y-2"
          >
            {endpoints.map((endpoint) => (
              <div
                key={endpoint.id}
                className={cn(
                  'rounded-lg border p-3 transition-colors',
                  selectedEndpoint === endpoint.id ? 'border-primary/40 bg-accent' : 'border-rail hover:bg-secondary/60',
                )}
              >
                <div className="flex items-start justify-between gap-2">
                  <button
                    type="button"
                    onClick={() => setSelectedEndpoint(endpoint.id)}
                    aria-current={selectedEndpoint === endpoint.id}
                    className="min-w-0 flex-1 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    <div className="truncate font-mono text-[13px] font-medium">{endpoint.slug}</div>
                    <div className="mt-0.5 flex items-center gap-1 text-[11px] text-muted-foreground">
                      <Clock className="h-3 w-3" aria-hidden />
                      <span className="font-mono">{timeRemaining(endpoint.expiresAt)}</span>
                    </div>
                  </button>
                  <div className="flex flex-shrink-0 items-center gap-1">
                    <Button variant="ghost" size="icon-sm" onClick={() => copyToClipboard(endpoint.url)} title={t('common.copy')} aria-label={t('common.copy')}>
                      <Copy className="h-3.5 w-3.5" />
                    </Button>
                    {canManageTestEndpoints && (
                      <Button variant="ghost" size="icon-sm" onClick={() => setDeleteId(endpoint.id)} title={t('common.delete')} aria-label={t('common.delete')} className="text-muted-foreground hover:text-halt">
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    )}
                  </div>
                </div>
                <div className="mt-2 flex items-center gap-2">
                  <code className="min-w-0 flex-1 truncate rounded bg-muted px-2 py-1 font-mono text-[11px]">{endpoint.url}</code>
                  <span className="flex-shrink-0 font-mono text-[11px] text-muted-foreground">
                    {t('testEndpoints.requestCount', { count: endpoint.requestCount })}
                  </span>
                </div>
              </div>
            ))}
          </WorkbenchPanel>
        }
        result={
          !selectedEndpoint ? (
            <ResultPlaceholder icon={Inbox} title={t('testEndpoints.selectEndpoint')} hint={t('testEndpoints.selectEndpointHint')} />
          ) : (
            <WorkbenchPanel
              eyebrow={t('testEndpoints.resultEyebrow')}
              title={t('testEndpoints.capturedRequests')}
              actions={
                <>
                  {canManageTestEndpoints && requests.length > 0 && (
                    <Button variant="ghost" size="sm" onClick={handleClearRequests} disabled={clearing} className="text-muted-foreground hover:text-halt">
                      {clearing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Eraser className="h-3.5 w-3.5" />}
                      {t('testEndpoints.clearRequests')}
                    </Button>
                  )}
                  <Button variant="ghost" size="sm" onClick={() => loadRequests(selectedEndpoint)} disabled={loadingRequests}>
                    <RefreshCw className={cn('h-3.5 w-3.5', loadingRequests && 'animate-spin')} />
                    {t('testEndpoints.refresh')}
                  </Button>
                </>
              }
              bodyClassName="space-y-2"
            >
              {loadingRequests ? (
                <SkeletonRows count={3} height="h-12" />
              ) : requests.length === 0 ? (
                <EmptyState
                  icon={Inbox}
                  title={t('testEndpoints.noRequests')}
                  description={t('testEndpoints.noRequestsHint')}
                  className="flex flex-col items-center justify-center py-10"
                />
              ) : (
                requests.map((req) => {
                  const expanded = expandedRequest === req.id;
                  return (
                    <div key={req.id} className="overflow-hidden rounded-lg border border-rail">
                      <button
                        type="button"
                        aria-expanded={expanded}
                        onClick={() => setExpandedRequest(expanded ? null : req.id)}
                        className="flex w-full items-center gap-3 p-3 text-left transition-colors hover:bg-secondary/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        {expanded
                          ? <ChevronDown className="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" aria-hidden />
                          : <ChevronRight className="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" aria-hidden />}
                        <span className="rounded border border-rail bg-secondary px-1.5 py-0.5 font-mono text-[11px] font-medium">
                          {req.method}
                        </span>
                        <span className="font-mono text-[11px] text-muted-foreground">{formatDateTime(req.receivedAt)}</span>
                        {req.sourceIp && (
                          <span className="ml-auto truncate font-mono text-[11px] text-muted-foreground">{req.sourceIp}</span>
                        )}
                      </button>

                      {expanded && (
                        <div className="space-y-3 border-t border-rail bg-muted/30 p-3">
                          {req.headers && (
                            <OutputBlock label={t('testEndpoints.headers')}>
                              <pre className="max-h-40 overflow-auto p-2.5 font-mono text-[11px]">
                                {JSON.stringify(parseHeaders(req.headers), null, 2)}
                              </pre>
                            </OutputBlock>
                          )}
                          {req.body && (
                            <div className="space-y-1.5">
                              <p className="mono-label">{t('testEndpoints.body')}</p>
                              <JsonEditor value={formatJson(req.body)} readOnly minHeight="140px" maxHeight="260px" />
                            </div>
                          )}
                          {req.queryString && (
                            <OutputBlock label={t('testEndpoints.queryString')}>
                              <code className="block break-all p-2.5 font-mono text-[11px]">{`?${req.queryString}`}</code>
                            </OutputBlock>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })
              )}
            </WorkbenchPanel>
          )
        }
      />

      <AlertDialog open={!!deleteId} onOpenChange={(open) => !open && setDeleteId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('testEndpoints.deleteDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('testEndpoints.deleteDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} disabled={deleting} className={buttonVariants({ variant: 'destructive' })}>
              {deleting && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('common.delete')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

