import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pencil, Plus, RefreshCw, Trash2, Webhook } from 'lucide-react';
import {
  useCreatePortalEndpoint, useDeletePortalEndpoint, usePortalEndpoints, useRotatePortalSecret,
  useUpdatePortalEndpoint,
} from '../../api/queries';
import type { PortalEndpointRequest, PortalEndpointResponse } from '../../types/api.types';
import { showApiError, showSuccess } from '../../lib/toast';
import { formatDate } from '../../lib/date';
import EmptyState, { ErrorState } from '../../components/EmptyState';
import { SkeletonRows } from '../../components/PageSkeleton';
import { EnabledBadge } from '../../components/StatusBadge';
import ConfirmDialog from '../../components/ConfirmDialog';
import SecretField from '../../components/SecretField';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Card } from '../../components/ui/card';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../../components/ui/dialog';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../../components/ui/table';
import PortalEndpointDialog from './PortalEndpointDialog';

/** How many event types a row shows before it says "and N more". */
const TYPES_SHOWN = 3;

export default function PortalEndpoints({ eventTypes }: { eventTypes: string[] }) {
  const { t } = useTranslation();
  const endpoints = usePortalEndpoints(true);
  const createEndpoint = useCreatePortalEndpoint();
  const updateEndpoint = useUpdatePortalEndpoint();
  const deleteEndpoint = useDeletePortalEndpoint();
  const rotateSecret = useRotatePortalSecret();

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<PortalEndpointResponse | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [rotateId, setRotateId] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<PortalEndpointResponse | null>(null);

  const handleCreate = async (data: PortalEndpointRequest) => {
    try {
      const created = await createEndpoint.mutateAsync(data);
      setCreating(false);
      setRevealed(created);
      showSuccess(t('portal.endpoints.toast.created'));
    } catch (err) {
      showApiError(err, 'portal.endpoints.toast.createFailed');
    }
  };

  const handleUpdate = async (data: PortalEndpointRequest) => {
    if (!editing) return;
    try {
      await updateEndpoint.mutateAsync({ id: editing.id, data });
      setEditing(null);
      showSuccess(t('portal.endpoints.toast.updated'));
    } catch (err) {
      showApiError(err, 'portal.endpoints.toast.updateFailed');
    }
  };

  const handleDelete = async () => {
    if (!deleteId) return;
    try {
      await deleteEndpoint.mutateAsync(deleteId);
      setDeleteId(null);
      showSuccess(t('portal.endpoints.toast.deleted'));
    } catch (err) {
      showApiError(err, 'portal.endpoints.toast.deleteFailed');
    }
  };

  const handleRotate = async () => {
    if (!rotateId) return;
    try {
      const rotated = await rotateSecret.mutateAsync(rotateId);
      setRotateId(null);
      setRevealed(rotated);
    } catch (err) {
      showApiError(err, 'portal.endpoints.toast.rotateFailed');
    }
  };

  if (endpoints.isLoading) return <SkeletonRows count={3} height="h-14" />;
  if (endpoints.isError) {
    return <ErrorState error={endpoints.error} fallbackKey="portal.endpoints.loadFailed" onRetry={() => endpoints.refetch()} />;
  }

  const list = endpoints.data ?? [];
  const addButton = (
    <Button onClick={() => setCreating(true)}>
      <Plus className="h-4 w-4" /> {t('portal.endpoints.add')}
    </Button>
  );

  return (
    <div>
      {list.length === 0 ? (
        <EmptyState
          icon={Webhook}
          title={t('portal.endpoints.empty.title')}
          description={t('portal.endpoints.empty.description')}
          action={addButton}
        />
      ) : (
        <>
          <div className="mb-3 flex items-center justify-between gap-3">
            <p className="text-sm text-muted-foreground">{t('portal.endpoints.description')}</p>
            {addButton}
          </div>
          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('portal.endpoints.url')}</TableHead>
                  <TableHead>{t('portal.endpoints.eventTypes')}</TableHead>
                  <TableHead>{t('portal.endpoints.status')}</TableHead>
                  <TableHead>{t('portal.endpoints.created')}</TableHead>
                  <TableHead className="w-[120px] text-right">
                    <span className="sr-only">{t('common.actions')}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.map((endpoint) => (
                  <TableRow key={endpoint.id}>
                    <TableCell className="max-w-[320px]">
                      <div className="truncate font-mono text-[13px]" title={endpoint.url}>{endpoint.url}</div>
                      {endpoint.description && (
                        <div className="truncate text-xs text-muted-foreground">{endpoint.description}</div>
                      )}
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {endpoint.eventTypes.length === 0 && (
                          <span className="text-xs text-muted-foreground">{t('portal.endpoints.noEventTypes')}</span>
                        )}
                        {endpoint.eventTypes.slice(0, TYPES_SHOWN).map((type) => (
                          <Badge key={type} variant="outline" className="font-mono text-[11px]">
                            {type === '**' ? t('portal.eventTypes.all') : type}
                          </Badge>
                        ))}
                        {endpoint.eventTypes.length > TYPES_SHOWN && (
                          <span className="text-xs text-muted-foreground">
                            {t('portal.endpoints.moreTypes', { count: endpoint.eventTypes.length - TYPES_SHOWN })}
                          </span>
                        )}
                      </div>
                    </TableCell>
                    <TableCell><EnabledBadge enabled={endpoint.enabled} /></TableCell>
                    <TableCell>
                      <span className="font-mono text-[11px] text-muted-foreground">{formatDate(endpoint.createdAt)}</span>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-1">
                        <Button
                          variant="ghost" size="icon-sm" onClick={() => setEditing(endpoint)}
                          title={t('common.edit')} aria-label={t('common.edit')}
                        >
                          <Pencil className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          variant="ghost" size="icon-sm" onClick={() => setRotateId(endpoint.id)}
                          title={t('portal.endpoints.rotateSecret')} aria-label={t('portal.endpoints.rotateSecret')}
                        >
                          <RefreshCw className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          variant="ghost" size="icon-sm" onClick={() => setDeleteId(endpoint.id)}
                          title={t('common.delete')} aria-label={t('common.delete')}
                          className="text-muted-foreground hover:text-halt"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        </>
      )}

      <PortalEndpointDialog
        open={creating}
        onOpenChange={setCreating}
        eventTypes={eventTypes}
        saving={createEndpoint.isPending}
        onSubmit={handleCreate}
      />

      <PortalEndpointDialog
        open={!!editing}
        onOpenChange={(open) => !open && setEditing(null)}
        eventTypes={eventTypes}
        endpoint={editing ?? undefined}
        saving={updateEndpoint.isPending}
        onSubmit={handleUpdate}
      />

      <ConfirmDialog
        open={!!deleteId}
        onOpenChange={(open) => !open && setDeleteId(null)}
        title={t('portal.endpoints.deleteDialog.title')}
        description={t('portal.endpoints.deleteDialog.description')}
        onConfirm={handleDelete}
        loading={deleteEndpoint.isPending}
        confirmLabel={t('common.delete')}
        destructive
      />

      <ConfirmDialog
        open={!!rotateId}
        onOpenChange={(open) => !open && setRotateId(null)}
        title={t('portal.endpoints.rotateDialog.title')}
        description={t('portal.endpoints.rotateDialog.description')}
        onConfirm={handleRotate}
        loading={rotateSecret.isPending}
        confirmLabel={t('portal.endpoints.rotateDialog.confirm')}
      />

      {/* Shown once: the API returns the secret only with the create or the rotation. */}
      <Dialog open={!!revealed?.secret} onOpenChange={(open) => !open && setRevealed(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('portal.secret.title')}</DialogTitle>
            <DialogDescription>{t('portal.secret.description')}</DialogDescription>
          </DialogHeader>
          {revealed?.secret && <SecretField secret={revealed.secret} label={t('portal.secret.label')} />}
          {revealed?.standardWebhooksSecret && (
            <SecretField secret={revealed.standardWebhooksSecret} label={t('portal.secret.standardLabel')} />
          )}
          <DialogFooter>
            <Button onClick={() => setRevealed(null)}>{t('portal.secret.done')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
