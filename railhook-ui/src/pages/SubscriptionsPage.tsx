import { useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Bell, Loader2, MoveRight, Plus, Power, PowerOff, Settings, Trash2,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { showSuccess, showApiError } from '../lib/toast';
import { formatDate } from '../lib/date';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { subscriptionsApi, type SubscriptionResponse } from '../api/subscriptions.api';
import {
  useProject, useSubscriptions, useEndpoints, useEventTypes, usePatchSubscription,
  useDeleteSubscription, queryKeys,
} from '../api/queries';
import { Button } from '../components/ui/button';
import { Card, CardContent } from '../components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { Badge } from '../components/ui/badge';
import { Select } from '../components/ui/select';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Switch } from '../components/ui/switch';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import CreateSubscriptionModal from '../components/CreateSubscriptionModal';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import ConfirmDialog from '../components/ConfirmDialog';

/**
 * The flat list of Subscriptions — the other half of a connection. One row is
 * one standing statement that an endpoint wants events of a given type, which
 * is why the event type leads the row and the endpoint follows it.
 */
export default function SubscriptionsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageSubscriptions } = usePermissions();
  const qc = useQueryClient();

  const { data: project, isLoading: projectLoading } = useProject(projectId);
  const {
    data: subscriptions = [], isLoading: subsLoading, isError: subsFailed,
    error: subsError, refetch: refetchSubs,
  } = useSubscriptions(projectId);
  const {
    data: endpoints = [], isLoading: endpointsLoading, isError: endpointsFailed,
    error: endpointsError, refetch: refetchEndpoints,
  } = useEndpoints(projectId);
  const { data: catalogTypes = [] } = useEventTypes(projectId);

  const patchMutation = usePatchSubscription(projectId!);
  const deleteMutation = useDeleteSubscription(projectId!);

  const schemaByName = new Map(catalogTypes.map((et) => [et.name, et]));
  const loading = projectLoading || subsLoading || endpointsLoading;
  const failed = subsFailed || endpointsFailed;

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [editingSubscription, setEditingSubscription] = useState<SubscriptionResponse | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);

  const [eventTypeFilter, setEventTypeFilter] = useState('');
  const [endpointFilter, setEndpointFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');

  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [bulkProcessing, setBulkProcessing] = useState(false);
  const [showMoveDialog, setShowMoveDialog] = useState(false);
  const [moveToEndpointId, setMoveToEndpointId] = useState('');

  const handleToggleEnabled = (subscription: SubscriptionResponse) => {
    patchMutation.mutate(
      { id: subscription.id, data: { enabled: !subscription.enabled } },
      {
        onSuccess: () => showSuccess(
          subscription.enabled ? t('subscriptions.toast.disabled') : t('subscriptions.toast.enabled')
        ),
      }
    );
  };

  const handleToggleOrdering = (subscription: SubscriptionResponse) => {
    patchMutation.mutate(
      { id: subscription.id, data: { orderingEnabled: !subscription.orderingEnabled } },
      {
        onSuccess: () => showSuccess(
          subscription.orderingEnabled ? t('subscriptions.toast.fifoDisabled') : t('subscriptions.toast.fifoEnabled')
        ),
      }
    );
  };

  const handleDelete = () => {
    if (!deleteId) return;
    deleteMutation.mutate(deleteId, {
      onSuccess: () => { showSuccess(t('subscriptions.toast.deleted')); setDeleteId(null); },
    });
  };

  const endpointUrl = (endpointId: string) => {
    const endpoint = endpoints.find((e) => e.id === endpointId);
    return endpoint ? endpoint.url : t('subscriptions.unknown');
  };

  const toggleSelect = (id: string) =>
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const handleBulkEnable = async (enable: boolean) => {
    if (!projectId) return;
    setBulkProcessing(true);
    try {
      const ids = Array.from(selectedIds);
      await Promise.all(ids.map((id) => subscriptionsApi.patch(projectId, id, { enabled: enable })));
      showSuccess(t('subscriptions.bulk.done', { count: ids.length }));
      setSelectedIds(new Set());
      qc.invalidateQueries({ queryKey: queryKeys.subscriptions.list(projectId) });
    } catch (err) {
      showApiError(err, 'toast.errors.server');
    } finally {
      setBulkProcessing(false);
    }
  };

  /** Point the selected subscriptions at a different endpoint. */
  const handleBulkMove = async () => {
    if (!projectId || !moveToEndpointId) return;
    setBulkProcessing(true);
    try {
      const ids = Array.from(selectedIds);
      await Promise.all(ids.map((id) => {
        const sub = subscriptions.find((s) => s.id === id);
        if (!sub) return Promise.resolve();
        return subscriptionsApi.update(projectId, id, {
          endpointId: moveToEndpointId,
          eventType: sub.eventType,
          enabled: sub.enabled,
          orderingEnabled: sub.orderingEnabled,
          maxAttempts: sub.maxAttempts,
          timeoutSeconds: sub.timeoutSeconds,
          retryDelays: sub.retryDelays,
          payloadTemplate: sub.payloadTemplate || undefined,
          customHeaders: sub.customHeaders || undefined,
          transformationId: sub.transformationId,
        });
      }));
      showSuccess(t('subscriptions.bulk.moved', 'Moved {{count}} subscriptions', { count: ids.length }));
      setSelectedIds(new Set());
      setShowMoveDialog(false);
      setMoveToEndpointId('');
      qc.invalidateQueries({ queryKey: queryKeys.subscriptions.list(projectId) });
    } catch (err) {
      showApiError(err, 'toast.errors.server');
    } finally {
      setBulkProcessing(false);
    }
  };

  const filtered = subscriptions.filter((sub) => {
    if (eventTypeFilter && !sub.eventType.toLowerCase().includes(eventTypeFilter.toLowerCase())) return false;
    if (endpointFilter && sub.endpointId !== endpointFilter) return false;
    if (statusFilter === 'enabled' && !sub.enabled) return false;
    if (statusFilter === 'disabled' && sub.enabled) return false;
    return true;
  });

  if (loading) {
    return (
      <PageSkeleton maxWidth="max-w-7xl">
        <SkeletonRows count={4} height="h-16" />
      </PageSkeleton>
    );
  }

  const newSubscriptionButton = (
    <PermissionGate allowed={canManageSubscriptions}>
      <VerificationGate>
        <Button onClick={() => { setEditingSubscription(null); setShowCreateModal(true); }}>
          <Plus className="h-4 w-4" /> {t('subscriptions.newSubscription')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={project?.name}
        title={t('subscriptions.title')}
        description={t('subscriptions.descriptionV2', 'Each row is one endpoint asking for one type of event. An event with no matching subscription is delivered nowhere.')}
        actions={!failed && subscriptions.length > 0 ? newSubscriptionButton : undefined}
      />

      {failed ? (
        <ErrorState
          error={subsError ?? endpointsError}
          fallbackKey="endpoints.toast.loadFailed"
          onRetry={() => { refetchSubs(); refetchEndpoints(); }}
        />
      ) : subscriptions.length === 0 ? (
        <div className="space-y-4">
          <EmptyState
            icon={Bell}
            title={t('subscriptions.noSubscriptions')}
            description={t('subscriptions.noSubscriptionsDesc')}
            action={newSubscriptionButton}
            docsLink="/docs#subscriptions-api"
          />
          {catalogTypes.length === 0 && endpoints.length > 0 && (
            <p className="text-center text-xs text-muted-foreground">{t('subscriptions.noEventTypesHint')}</p>
          )}
        </div>
      ) : (
        <>
          <Card className="mb-4">
            <CardContent className="grid grid-cols-1 gap-3 p-4 md:grid-cols-3">
              <div className="space-y-1.5">
                <Label htmlFor="eventTypeFilter" className="text-xs">{t('subscriptions.eventType')}</Label>
                <Input
                  id="eventTypeFilter"
                  className="font-mono text-sm"
                  placeholder={t('subscriptions.filterEventType')}
                  value={eventTypeFilter}
                  onChange={(e) => setEventTypeFilter(e.target.value)}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="endpointFilter" className="text-xs">{t('subscriptions.endpoint')}</Label>
                <Select id="endpointFilter" value={endpointFilter} onChange={(e) => setEndpointFilter(e.target.value)}>
                  <option value="">{t('subscriptions.allEndpoints')}</option>
                  {endpoints.map((endpoint) => (
                    <option key={endpoint.id} value={endpoint.id}>{endpoint.url}</option>
                  ))}
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="statusFilter" className="text-xs">{t('subscriptions.status')}</Label>
                <Select id="statusFilter" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
                  <option value="">{t('subscriptions.allStatuses')}</option>
                  <option value="enabled">{t('common.enabled')}</option>
                  <option value="disabled">{t('common.disabled')}</option>
                </Select>
              </div>
            </CardContent>
          </Card>

          {selectedIds.size > 0 && canManageSubscriptions && (
            <Card className="mb-4 border-primary/40">
              <CardContent className="flex flex-wrap items-center gap-3 p-3">
                <span className="text-sm font-medium">
                  {t('subscriptions.bulk.selected', { count: selectedIds.size })}
                </span>
                <div className="ml-auto flex items-center gap-2">
                  <VerificationGate>
                    <Button size="sm" variant="outline" onClick={() => handleBulkEnable(true)} disabled={bulkProcessing}>
                      {bulkProcessing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Power className="h-3.5 w-3.5" />}
                      {t('subscriptions.bulk.enableAll')}
                    </Button>
                  </VerificationGate>
                  <VerificationGate>
                    <Button size="sm" variant="outline" onClick={() => handleBulkEnable(false)} disabled={bulkProcessing}>
                      {bulkProcessing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <PowerOff className="h-3.5 w-3.5" />}
                      {t('subscriptions.bulk.disableAll')}
                    </Button>
                  </VerificationGate>
                  <VerificationGate>
                    <Button size="sm" variant="outline" onClick={() => setShowMoveDialog(true)} disabled={bulkProcessing}>
                      <MoveRight className="h-3.5 w-3.5" />
                      {t('subscriptions.bulk.move', 'Move to another endpoint')}
                    </Button>
                  </VerificationGate>
                  <Button size="sm" variant="ghost" onClick={() => setSelectedIds(new Set())}>
                    {t('common.cancel')}
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {filtered.length === 0 ? (
            <EmptyState
              icon={Bell}
              title={t('subscriptions.noMatching')}
              description={t('subscriptions.noMatchingDesc')}
              action={
                <Button
                  variant="outline"
                  onClick={() => { setEventTypeFilter(''); setEndpointFilter(''); setStatusFilter(''); }}
                >
                  {t('subscriptions.clearFilters', 'Clear filters')}
                </Button>
              }
            />
          ) : (
            <Card className="overflow-hidden">
              <Table>
                <TableHeader>
                  <TableRow>
                    {canManageSubscriptions && (
                      <TableHead className="w-[40px]">
                        <input
                          type="checkbox"
                          className="h-4 w-4 cursor-pointer rounded border-rail accent-primary"
                          aria-label={t('subscriptions.selectAll', 'Select every subscription shown')}
                          checked={selectedIds.size === filtered.length && filtered.length > 0}
                          onChange={() =>
                            setSelectedIds(
                              selectedIds.size === filtered.length ? new Set() : new Set(filtered.map((s) => s.id))
                            )
                          }
                        />
                      </TableHead>
                    )}
                    <TableHead>{t('subscriptions.eventType')}</TableHead>
                    <TableHead>{t('subscriptions.endpoint')}</TableHead>
                    <TableHead>{t('subscriptions.status')}</TableHead>
                    <TableHead>{t('subscriptions.ordering')}</TableHead>
                    <TableHead>{t('subscriptions.created')}</TableHead>
                    {canManageSubscriptions && (
                      <TableHead className="w-[80px]"><span className="sr-only">{t('common.actions')}</span></TableHead>
                    )}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filtered.map((subscription) => {
                    const schema = schemaByName.get(subscription.eventType);
                    return (
                      <TableRow key={subscription.id} data-state={selectedIds.has(subscription.id) ? 'selected' : undefined}>
                        {canManageSubscriptions && (
                          <TableCell>
                            <input
                              type="checkbox"
                              className="h-4 w-4 cursor-pointer rounded border-rail accent-primary"
                              aria-label={t('subscriptions.selectOne', 'Select {{eventType}}', { eventType: subscription.eventType })}
                              checked={selectedIds.has(subscription.id)}
                              onChange={() => toggleSelect(subscription.id)}
                            />
                          </TableCell>
                        )}
                        <TableCell>
                          <div className="flex items-center gap-1.5">
                            <code className="font-mono text-[13px]">{subscription.eventType}</code>
                            {schema?.latestVersion != null && (
                              <Badge variant="outline" className="font-mono text-[10px]">v{schema.latestVersion}</Badge>
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="max-w-[260px]">
                          <span className="block truncate font-mono text-[13px]" title={endpointUrl(subscription.endpointId)}>
                            {endpointUrl(subscription.endpointId)}
                          </span>
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-2">
                            <Switch
                              checked={subscription.enabled}
                              onCheckedChange={() => handleToggleEnabled(subscription)}
                              disabled={!canManageSubscriptions}
                              aria-label={t('connections.toggleSubscription', 'Enable {{eventType}}', { eventType: subscription.eventType })}
                            />
                            <Badge variant={subscription.enabled ? 'ok' : 'idle'}>
                              {subscription.enabled ? t('common.on') : t('common.off')}
                            </Badge>
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-2">
                            <Switch
                              checked={subscription.orderingEnabled}
                              onCheckedChange={() => handleToggleOrdering(subscription)}
                              disabled={!canManageSubscriptions}
                              aria-label={t('subscriptions.toggleOrdering', 'Keep {{eventType}} in sequence', { eventType: subscription.eventType })}
                            />
                            {subscription.orderingEnabled && (
                              <Badge variant="outline" className="font-mono text-[10px]">{t('subscriptions.fifo')}</Badge>
                            )}
                          </div>
                        </TableCell>
                        <TableCell>
                          <span className="font-mono text-[11px] text-muted-foreground">
                            {formatDate(subscription.createdAt)}
                          </span>
                        </TableCell>
                        {canManageSubscriptions && (
                          <TableCell>
                            <div className="flex gap-1">
                              <Button
                                variant="ghost" size="icon-sm"
                                onClick={() => { setEditingSubscription(subscription); setShowCreateModal(true); }}
                                title={t('common.edit')} aria-label={t('common.edit')}
                              >
                                <Settings className="h-3.5 w-3.5" />
                              </Button>
                              <Button
                                variant="ghost" size="icon-sm"
                                onClick={() => setDeleteId(subscription.id)}
                                title={t('common.delete')} aria-label={t('common.delete')}
                                className="text-muted-foreground hover:text-halt"
                              >
                                <Trash2 className="h-3.5 w-3.5" />
                              </Button>
                            </div>
                          </TableCell>
                        )}
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </Card>
          )}
        </>
      )}

      <CreateSubscriptionModal
        projectId={projectId!}
        endpoints={endpoints}
        subscription={editingSubscription}
        open={showCreateModal}
        onClose={() => { setShowCreateModal(false); setEditingSubscription(null); }}
        onSuccess={() => qc.invalidateQueries({ queryKey: queryKeys.subscriptions.list(projectId!) })}
      />

      <ConfirmDialog
        open={!!deleteId}
        onOpenChange={(open) => !open && setDeleteId(null)}
        title={t('subscriptions.deleteDialog.title')}
        description={t('subscriptions.deleteDialog.description')}
        onConfirm={handleDelete}
        loading={deleteMutation.isPending}
      />

      <Dialog
        open={showMoveDialog}
        onOpenChange={(open) => { if (!open) { setShowMoveDialog(false); setMoveToEndpointId(''); } }}
      >
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{t('subscriptions.bulk.moveTitle', 'Move to another endpoint')}</DialogTitle>
            <DialogDescription>
              {t('subscriptions.bulk.moveDesc', 'These {{count}} subscriptions will start delivering to the endpoint you pick.', { count: selectedIds.size })}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5 py-2">
            <Label htmlFor="moveEndpoint" className="text-xs">{t('subscriptions.endpoint')}</Label>
            <Select id="moveEndpoint" value={moveToEndpointId} onChange={(e) => setMoveToEndpointId(e.target.value)}>
              <option value="">{t('subscriptions.bulk.selectEndpointV2', 'Pick an endpoint…')}</option>
              {endpoints.map((ep) => (
                <option key={ep.id} value={ep.id}>{ep.url}</option>
              ))}
            </Select>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => { setShowMoveDialog(false); setMoveToEndpointId(''); }}
              disabled={bulkProcessing}
            >
              {t('common.cancel')}
            </Button>
            <Button onClick={handleBulkMove} disabled={bulkProcessing || !moveToEndpointId}>
              {bulkProcessing && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('subscriptions.bulk.moveConfirm', 'Move')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
