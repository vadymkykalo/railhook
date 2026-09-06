import { useState, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { Repeat2, Plus, Loader2, Trash2, Settings, Copy, Wand2, Search, ArrowDown, Link2 } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import { formatDate } from '../lib/date';
import PageSkeleton from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { EnabledBadge } from '../components/StatusBadge';
import {
  useProject,
  useTransformations,
  useCreateTransformation,
  useUpdateTransformation,
  useDeleteTransformation,
} from '../api/queries';
import { Button, buttonVariants } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Switch } from '../components/ui/switch';
import JsonEditor from '../components/JsonEditor';
import { OutputBlock } from '../components/Workbench';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import type { TransformationResponse, TransformationRequest } from '../types/api.types';
import { formatJson, isValidJson } from '../lib/json';

const SAMPLE_INPUT = {
  id: 'evt_abc123',
  type: 'order.created',
  data: { orderId: 'ord_456', amount: 99.99, currency: 'USD' },
  createdAt: '2026-03-04T11:00:00Z',
};

export default function TransformationsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageSubscriptions: canManage } = usePermissions();
  const {
    data: project, isLoading: projectLoading, isError: projectFailed,
    error: projectError, refetch: refetchProject, isRefetching,
  } = useProject(projectId);
  const {
    data: transformations = [], isLoading: listLoading, isError: listFailed,
    error: listError, refetch: refetchList,
  } = useTransformations(projectId!);
  const createMutation = useCreateTransformation(projectId!);
  const updateMutation = useUpdateTransformation(projectId!);
  const deleteMutation = useDeleteTransformation(projectId!);

  const [showDialog, setShowDialog] = useState(false);
  const [editing, setEditing] = useState<TransformationResponse | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [searchFilter, setSearchFilter] = useState('');

  const [formName, setFormName] = useState('');
  const [formDescription, setFormDescription] = useState('');
  const [formTemplate, setFormTemplate] = useState('');
  const [formEnabled, setFormEnabled] = useState(true);
  const [formTouched, setFormTouched] = useState(false);

  const templateHasContent = formTemplate.trim().length > 0;
  const templateIsJson = templateHasContent && isValidJson(formTemplate);
  const exprCount = (formTemplate.match(/\$\{[^}]*\}/g) || []).length;

  /** What the sample event would come out as, resolved locally. */
  const livePreview = useMemo(() => {
    if (!templateHasContent || !templateIsJson) return null;
    try {
      let result = formTemplate;
      const exprRegex = /\$\{([^}]+)\}/g;
      let match;
      while ((match = exprRegex.exec(formTemplate)) !== null) {
        const path = match[1];
        const parts = path.replace(/^\$\.?/, '').split('.');
        let value: any = SAMPLE_INPUT;
        for (const p of parts) {
          if (value && typeof value === 'object' && p in value) value = value[p];
          else { value = `<${path}>`; break; }
        }
        result = result.replace(match[0], typeof value === 'object' ? JSON.stringify(value) : String(value));
      }
      return JSON.stringify(JSON.parse(result), null, 2);
    } catch {
      return null;
    }
  }, [formTemplate, templateHasContent, templateIsJson]);

  const loading = projectLoading || listLoading;

  const openCreate = () => {
    setEditing(null);
    setFormName('');
    setFormDescription('');
    setFormTemplate('{\n  "event_type": "${$.type}",\n  "data": "${$.data}"\n}');
    setFormEnabled(true);
    setFormTouched(false);
    setShowDialog(true);
  };

  const openEdit = (item: TransformationResponse) => {
    setEditing(item);
    setFormName(item.name);
    setFormDescription(item.description || '');
    setFormTemplate(formatJson(item.template));
    setFormEnabled(item.enabled);
    setFormTouched(false);
    setShowDialog(true);
  };

  const handleDuplicate = (item: TransformationResponse) => {
    setEditing(null);
    setFormName(t('transformations.copyOfName', { name: item.name }));
    setFormDescription(item.description || '');
    setFormTemplate(formatJson(item.template));
    setFormEnabled(true);
    setFormTouched(false);
    setShowDialog(true);
  };

  const handleSave = () => {
    setFormTouched(true);
    if (!formName.trim() || !formTemplate.trim() || !isValidJson(formTemplate)) return;

    const data: TransformationRequest = {
      name: formName.trim(),
      description: formDescription.trim() || undefined,
      template: formTemplate.trim(),
      enabled: formEnabled,
    };

    if (editing) {
      updateMutation.mutate({ id: editing.id, data }, {
        onSuccess: () => { showSuccess(t('transformations.toast.updated')); setShowDialog(false); },
        onError: (err) => showApiError(err, t('transformations.toast.updateFailed')),
      });
    } else {
      createMutation.mutate(data, {
        onSuccess: () => { showSuccess(t('transformations.toast.created')); setShowDialog(false); },
        onError: (err) => showApiError(err, t('transformations.toast.createFailed')),
      });
    }
  };

  const handleDelete = () => {
    if (!deleteId) return;
    deleteMutation.mutate(deleteId, {
      onSuccess: () => { showSuccess(t('transformations.toast.deleted')); setDeleteId(null); },
      onError: (err) => showApiError(err, t('transformations.toast.deleteFailed')),
    });
  };

  const filtered = transformations.filter((item) => {
    if (!searchFilter) return true;
    const q = searchFilter.toLowerCase();
    return item.name.toLowerCase().includes(q) || (item.description || '').toLowerCase().includes(q);
  });

  const saving = createMutation.isPending || updateMutation.isPending;

  if (loading) return <PageSkeleton />;

  // A failed fetch used to render "project not found", which reads as a
  // deleted project rather than a backend that is down.
  if (projectFailed || listFailed || !project) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState
          error={projectError ?? listError}
          onRetry={() => { refetchProject(); refetchList(); }}
          retrying={isRefetching}
        />
      </div>
    );
  }

  const createButton = (label: string) => (
    <PermissionGate allowed={canManage}>
      <VerificationGate>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> {label}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('transformations.count', { count: transformations.length })}
        title={t('transformations.title')}
        description={t('transformations.subtitle')}
        actions={transformations.length > 0 ? createButton(t('transformations.create')) : undefined}
      />

      {transformations.length > 0 && (
        <div className="relative mb-4 max-w-sm">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input
            aria-label={t('transformations.search')}
            placeholder={t('transformations.searchPlaceholder')}
            value={searchFilter}
            onChange={(e) => setSearchFilter(e.target.value)}
            className="pl-9"
          />
        </div>
      )}

      {transformations.length === 0 ? (
        <EmptyState
          icon={Repeat2}
          title={t('transformations.empty')}
          description={t('transformations.emptyDesc')}
          action={createButton(t('transformations.createFirst'))}
        />
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={Search}
          title={t('transformations.noMatching')}
          description={t('transformations.noMatchingDesc')}
        />
      ) : (
        <Card className="overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('transformations.name')}</TableHead>
                <TableHead>{t('transformations.description')}</TableHead>
                <TableHead>{t('transformations.version')}</TableHead>
                <TableHead>{t('transformations.status')}</TableHead>
                <TableHead>{t('transformations.usedBy')}</TableHead>
                <TableHead>{t('transformations.updated')}</TableHead>
                {canManage && <TableHead className="w-[110px]"><span className="sr-only">{t('common.actions')}</span></TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((item) => (
                <TableRow key={item.id}>
                  <TableCell><span className="text-[13px] font-medium">{item.name}</span></TableCell>
                  <TableCell>
                    <span className="block max-w-[250px] truncate text-[13px] text-muted-foreground">
                      {item.description || '—'}
                    </span>
                  </TableCell>
                  <TableCell><span className="font-mono text-xs">v{item.version}</span></TableCell>
                  <TableCell><EnabledBadge enabled={item.enabled} /></TableCell>
                  <TableCell>
                    {(item.subscriptionCount > 0 || item.destinationCount > 0) ? (
                      <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                        <Link2 className="h-3 w-3" aria-hidden />
                        <span>
                          {item.subscriptionCount > 0 && t('transformations.subscriptionCount', { count: item.subscriptionCount })}
                          {item.subscriptionCount > 0 && item.destinationCount > 0 && ' · '}
                          {item.destinationCount > 0 && t('transformations.destinationCount', { count: item.destinationCount })}
                        </span>
                      </span>
                    ) : (
                      <span className="text-xs text-muted-foreground">—</span>
                    )}
                  </TableCell>
                  <TableCell><span className="font-mono text-xs text-muted-foreground">{formatDate(item.updatedAt)}</span></TableCell>
                  {canManage && (
                    <TableCell>
                      <div className="flex gap-1">
                        <Button variant="ghost" size="icon-sm" onClick={() => openEdit(item)} title={t('common.edit')} aria-label={t('common.edit')}>
                          <Settings className="h-3.5 w-3.5" />
                        </Button>
                        <Button variant="ghost" size="icon-sm" onClick={() => handleDuplicate(item)} title={t('transformations.duplicate')} aria-label={t('transformations.duplicate')}>
                          <Copy className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          onClick={() => setDeleteId(item.id)}
                          title={t('common.delete')}
                          aria-label={t('common.delete')}
                          className="text-muted-foreground hover:text-halt"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      {/* Create / edit */}
      <Dialog open={showDialog} onOpenChange={setShowDialog}>
        <DialogContent className="sm:max-w-4xl">
          <DialogHeader>
            <DialogTitle>{editing ? t('transformations.editTitle') : t('transformations.createTitle')}</DialogTitle>
            <DialogDescription>
              {editing ? t('transformations.editDesc') : t('transformations.createDesc')}
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-2">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="tf-name">{t('transformations.name')}</Label>
                <Input
                  id="tf-name"
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  placeholder={t('transformations.namePlaceholder')}
                  className={formTouched && !formName.trim() ? 'border-halt' : ''}
                />
                <p className={`text-[11px] ${formTouched && !formName.trim() ? 'text-halt' : 'text-muted-foreground'}`}>
                  {formTouched && !formName.trim() ? t('transformations.validation.nameRequired') : t('transformations.nameHint')}
                </p>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="tf-desc">
                  {t('transformations.description')}{' '}
                  <span className="text-xs font-normal text-muted-foreground">{t('common.optional')}</span>
                </Label>
                <Input
                  id="tf-desc"
                  value={formDescription}
                  onChange={(e) => setFormDescription(e.target.value)}
                  placeholder={t('transformations.descriptionPlaceholder')}
                />
                <p className="text-[11px] text-muted-foreground">{t('transformations.descriptionHint')}</p>
              </div>
            </div>

            <div className="grid gap-4 lg:grid-cols-2">
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <Label htmlFor="tf-template">{t('transformations.template')}</Label>
                  {templateHasContent && (
                    <Button type="button" variant="ghost" size="sm" onClick={() => setFormTemplate(formatJson(formTemplate))}>
                      <Wand2 className="h-3 w-3" /> {t('transformations.format')}
                    </Button>
                  )}
                </div>
                <JsonEditor
                  value={formTemplate}
                  onChange={setFormTemplate}
                  minHeight="220px"
                  maxHeight="300px"
                />
                <div className="flex items-center justify-between gap-2 text-[11px]">
                  <span className={templateHasContent && !templateIsJson ? 'text-halt' : 'text-muted-foreground'}>
                    {templateHasContent && !templateIsJson
                      ? t('transformations.validation.invalidJson')
                      : formTouched && !templateHasContent
                        ? t('transformations.validation.templateRequired')
                        : templateIsJson
                          ? t('transformations.validation.validJson')
                          : ''}
                  </span>
                  {templateIsJson && exprCount > 0 && (
                    <span className="font-mono text-muted-foreground">
                      {t('transformations.expressionCount', { count: exprCount })}
                    </span>
                  )}
                </div>
                <p className="text-[11px] text-muted-foreground [&_code]:rounded [&_code]:bg-muted [&_code]:px-1">
                  <Trans i18nKey="transformations.howItWorks.hint" components={{ code: <code /> }} />
                </p>
              </div>

              <div className="space-y-2">
                <Label>{t('transformations.livePreview')}</Label>
                <OutputBlock label={t('transformations.sampleInputEvent')}>
                  <pre className="max-h-[130px] overflow-auto p-2.5 font-mono text-[11px] text-muted-foreground">
                    {JSON.stringify(SAMPLE_INPUT, null, 2)}
                  </pre>
                </OutputBlock>
                <div className="flex justify-center">
                  <ArrowDown className="h-4 w-4 text-muted-foreground" aria-hidden />
                </div>
                <OutputBlock label={t('transformations.outputPreview')}>
                  {livePreview ? (
                    <pre className="max-h-[170px] overflow-auto p-2.5 font-mono text-[11px]">{livePreview}</pre>
                  ) : (
                    <p className="p-4 text-center text-[11px] text-muted-foreground">
                      {templateHasContent && !templateIsJson
                        ? t('transformations.previewFixJson')
                        : t('transformations.previewEmpty')}
                    </p>
                  )}
                </OutputBlock>
              </div>
            </div>

            <div className="flex items-center gap-3 rounded-lg border border-rail p-3">
              <Switch id="tf-enabled" checked={formEnabled} onCheckedChange={setFormEnabled} />
              <div>
                <Label htmlFor="tf-enabled" className="cursor-pointer">{t('common.enabled')}</Label>
                <p className="text-[11px] text-muted-foreground">
                  {formEnabled ? t('transformations.enabledHint') : t('transformations.disabledHint')}
                </p>
              </div>
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setShowDialog(false)} disabled={saving}>
              {t('common.cancel')}
            </Button>
            <Button onClick={handleSave} disabled={saving}>
              {saving && <Loader2 className="h-4 w-4 animate-spin" />}
              {editing ? t('common.save') : t('common.create')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete */}
      <AlertDialog open={!!deleteId} onOpenChange={() => setDeleteId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('transformations.deleteDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('transformations.deleteDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteMutation.isPending}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              disabled={deleteMutation.isPending}
              className={buttonVariants({ variant: 'destructive' })}
            >
              {deleteMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {deleteMutation.isPending ? t('common.deleting') : t('common.delete')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
