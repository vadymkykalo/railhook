import { useState, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import {
  GitBranch, Plus, Loader2, Trash2, Zap, Search, ChevronDown, ChevronUp,
  Filter, Route, Wand2, Ban, Tag, X, Pencil, PlusCircle, FolderPlus,
  type LucideIcon,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import { formatRelativeTime } from '../lib/date';
import PageSkeleton from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { EnabledBadge } from '../components/StatusBadge';
import { RuleStats, RuleRow, MatchExpression, RuleActionChip } from '../components/RuleLayout';
import {
  useProject, useRules, useCreateRule, useUpdateRule, useDeleteRule, useToggleRule,
  useEndpoints, useTransformations,
} from '../api/queries';
import type {
  RuleResponse, RuleRequest, RuleActionRequest, ActionType, ConditionNode,
} from '../api/rules.api';
import { Button, buttonVariants } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Switch } from '../components/ui/switch';
import { Select } from '../components/ui/select';
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
import ConditionTreeEditor, { mkGroup, mkPredicate, countPredicates, NO_VALUE_OPS } from '../components/ConditionTreeEditor';

/**
 * Rules: *when this matches, do that*. The PII rules page says the same
 * sentence about a different subject, so both are drawn with the pieces in
 * `RuleLayout` — see the note there.
 *
 * An action type used to carry its own colour (blue route, purple transform,
 * red drop). Those are not statuses, and the palette reserves colour for
 * statuses, so an action is now told apart by its icon and its name.
 */

const ACTION_ICON: Record<ActionType, LucideIcon> = {
  ROUTE: Route,
  TRANSFORM: Wand2,
  DROP: Ban,
  TAG: Tag,
};

function cloneNode(node: ConditionNode): ConditionNode {
  return JSON.parse(JSON.stringify(node));
}

export default function RulesPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageSubscriptions: canManage } = usePermissions();
  const {
    isLoading: projectLoading, isError: projectFailed, error: projectError, refetch: refetchProject,
  } = useProject(projectId);
  const {
    data: rules = [], isLoading: rulesLoading, isError: rulesFailed,
    error: rulesError, refetch: refetchRules, isRefetching,
  } = useRules(projectId!);
  const { data: endpoints = [] } = useEndpoints(projectId!);
  const { data: transformations = [] } = useTransformations(projectId!);

  const createMutation = useCreateRule(projectId!);
  const updateMutation = useUpdateRule(projectId!);
  const deleteMutation = useDeleteRule(projectId!);
  const toggleMutation = useToggleRule(projectId!);

  const [showDialog, setShowDialog] = useState(false);
  const [editing, setEditing] = useState<RuleResponse | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [searchFilter, setSearchFilter] = useState('');
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const [formName, setFormName] = useState('');
  const [formDescription, setFormDescription] = useState('');
  const [formEnabled, setFormEnabled] = useState(true);
  const [formPriority, setFormPriority] = useState(0);
  const [formEventTypePattern, setFormEventTypePattern] = useState('');
  const [formConditions, setFormConditions] = useState<ConditionNode | null>(null);
  const [formActions, setFormActions] = useState<RuleActionRequest[]>([]);

  const loading = projectLoading || rulesLoading;

  const filteredRules = useMemo(() => {
    if (!searchFilter) return rules;
    const q = searchFilter.toLowerCase();
    return rules.filter((r) =>
      r.name.toLowerCase().includes(q)
      || r.description?.toLowerCase().includes(q)
      || r.eventTypePattern?.toLowerCase().includes(q));
  }, [rules, searchFilter]);

  const resetForm = () => {
    setFormName('');
    setFormDescription('');
    setFormEnabled(true);
    setFormPriority(0);
    setFormEventTypePattern('');
    setFormConditions(null);
    setFormActions([]);
  };

  const openCreate = () => {
    setEditing(null);
    resetForm();
    setShowDialog(true);
  };

  const openEdit = (rule: RuleResponse) => {
    setEditing(rule);
    setFormName(rule.name);
    setFormDescription(rule.description || '');
    setFormEnabled(rule.enabled);
    setFormPriority(rule.priority);
    setFormEventTypePattern(rule.eventTypePattern || '');
    setFormConditions(rule.conditions ? cloneNode(rule.conditions) : null);
    setFormActions(rule.actions.map((a) => ({
      type: a.type,
      endpointId: a.endpointId || undefined,
      transformationId: a.transformationId || undefined,
      config: a.config,
      sortOrder: a.sortOrder,
    })));
    setShowDialog(true);
  };

  const handleSave = async () => {
    const data: RuleRequest = {
      name: formName,
      description: formDescription || undefined,
      enabled: formEnabled,
      priority: formPriority,
      eventTypePattern: formEventTypePattern || undefined,
      conditions: formConditions,
      actions: formActions.length > 0 ? formActions : undefined,
    };
    try {
      if (editing) {
        await updateMutation.mutateAsync({ id: editing.id, data });
        showSuccess(t('rules.updated'));
      } else {
        await createMutation.mutateAsync(data);
        showSuccess(t('rules.created'));
      }
      setShowDialog(false);
    } catch (err) { showApiError(err, 'rules.saveFailed'); }
  };

  const handleDelete = async () => {
    if (!deleteId) return;
    try {
      await deleteMutation.mutateAsync(deleteId);
      showSuccess(t('rules.deleted'));
      setDeleteId(null);
    } catch (err) { showApiError(err, 'rules.deleteFailed'); }
  };

  const handleToggle = async (rule: RuleResponse) => {
    try {
      await toggleMutation.mutateAsync({ id: rule.id, enabled: !rule.enabled });
      showSuccess(rule.enabled ? t('rules.disabled') : t('rules.enabled'));
    } catch (err) { showApiError(err, 'rules.toggleFailed'); }
  };

  const ensureRoot = (): ConditionNode => formConditions ?? mkGroup('AND');

  const addPredicateToRoot = () => {
    const root = ensureRoot();
    setFormConditions(root.type === 'group'
      ? { ...root, children: [...root.children, mkPredicate()] }
      : { type: 'group', op: 'AND', children: [root, mkPredicate()] });
  };

  const addGroupToRoot = () => {
    const root = ensureRoot();
    setFormConditions(root.type === 'group'
      ? { ...root, children: [...root.children, mkGroup('AND')] }
      : { type: 'group', op: 'AND', children: [root, mkGroup('AND')] });
  };

  const addAction = (type: ActionType) => setFormActions([...formActions, { type, sortOrder: formActions.length }]);
  const updateAction = (idx: number, patch: Partial<RuleActionRequest>) =>
    setFormActions(formActions.map((a, i) => (i === idx ? { ...a, ...patch } : a)));
  const removeAction = (idx: number) => setFormActions(formActions.filter((_, i) => i !== idx));

  const loadFailed = projectFailed || rulesFailed;

  if (loading) return <PageSkeleton />;

  const enabledCount = rules.filter((r) => r.enabled).length;
  const totalExecutions = rules.reduce((s, r) => s + r.totalExecutions, 0);
  const totalMatches = rules.reduce((s, r) => s + r.totalMatches, 0);
  const matchRate = totalExecutions > 0 ? `${((totalMatches / totalExecutions) * 100).toFixed(1)}%` : '—';

  const createButton = (
    <PermissionGate allowed={canManage}>
      <VerificationGate>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> {t('rules.createRule')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('rules.count', { count: rules.length })}
        title={t('rules.title')}
        description={t('rules.subtitle')}
        actions={!loadFailed && rules.length > 0 ? createButton : undefined}
      />

      {loadFailed ? (
        <ErrorState
          error={projectError ?? rulesError}
          onRetry={() => { refetchProject(); refetchRules(); }}
          retrying={isRefetching}
        />
      ) : rules.length === 0 ? (
        <EmptyState
          icon={GitBranch}
          title={t('rules.empty.title')}
          description={t('rules.empty.description')}
          action={createButton}
        />
      ) : (
        <div className="space-y-4">
          <RuleStats
            items={[
              { label: t('rules.stats.total'), value: rules.length },
              { label: t('rules.stats.active'), value: enabledCount },
              { label: t('rules.stats.executions'), value: totalExecutions.toLocaleString() },
              { label: t('rules.stats.matchRate'), value: matchRate },
            ]}
          />

          <div className="relative max-w-sm">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
            <Input
              aria-label={t('rules.search')}
              placeholder={t('rules.search')}
              value={searchFilter}
              onChange={(e) => setSearchFilter(e.target.value)}
              className="pl-9"
            />
          </div>

          {filteredRules.length === 0 ? (
            <p className="rounded-xl border border-dashed border-rail px-6 py-12 text-center text-sm text-muted-foreground">
              {t('rules.noResults')}
            </p>
          ) : (
            <ul className="space-y-2.5">
              {filteredRules.map((rule) => {
                const expanded = expandedId === rule.id;
                return (
                  <li key={rule.id}>
                    <RuleRow
                      muted={!rule.enabled}
                      name={rule.name}
                      meta={rule.priority > 0 && (
                        <Badge variant="outline" className="font-mono text-[10px]">P{rule.priority}</Badge>
                      )}
                      match={
                        <MatchExpression title={rule.eventTypePattern || undefined}>
                          {rule.eventTypePattern || '**'}
                        </MatchExpression>
                      }
                      then={
                        <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                          {rule.actions.length === 0 ? (
                            <span className="text-xs text-muted-foreground">{t('rules.noActions')}</span>
                          ) : rule.actions.map((a, i) => (
                            <RuleActionChip
                              key={i}
                              icon={ACTION_ICON[a.type]}
                              label={t(`rules.actionTypes.${a.type}`)}
                              detail={a.endpointUrl || a.transformationName || undefined}
                            />
                          ))}
                        </span>
                      }
                      status={<EnabledBadge enabled={rule.enabled} />}
                      controls={
                        <>
                          <Switch
                            checked={rule.enabled}
                            onCheckedChange={() => handleToggle(rule)}
                            disabled={!canManage}
                            aria-label={t(rule.enabled ? 'common.disable' : 'common.enable')}
                          />
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={() => setExpandedId(expanded ? null : rule.id)}
                            aria-expanded={expanded}
                            aria-label={t('rules.showDetails')}
                          >
                            {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                          </Button>
                        </>
                      }
                      footer={expanded ? (
                        <div className="space-y-4 border-t border-rail bg-muted/30 px-3.5 py-3">
                          {rule.description && <p className="text-sm text-muted-foreground">{rule.description}</p>}

                          <div className="flex flex-wrap gap-x-5 gap-y-1 font-mono text-[11px] text-muted-foreground">
                            <span>{t('rules.conditionCount', { count: countPredicates(rule.conditions) })}</span>
                            <span>{t('rules.stats.executions')}: {rule.totalExecutions.toLocaleString()}</span>
                            <span>{t('rules.stats.matches')}: {rule.totalMatches.toLocaleString()}</span>
                          </div>

                          {rule.conditions && countPredicates(rule.conditions) > 0 && (
                            <div>
                              <p className="mono-label mb-2 flex items-center gap-1.5">
                                <Filter className="h-3 w-3" aria-hidden />
                                {t('rules.conditionsLabel')}
                              </p>
                              <ConditionTreeDisplay node={rule.conditions} />
                            </div>
                          )}

                          <div className="flex flex-wrap items-center justify-between gap-2">
                            <p className="text-[11px] text-muted-foreground">
                              {t('rules.createdAt')} <span className="font-mono">{formatRelativeTime(rule.createdAt)}</span>
                              {rule.updatedAt !== rule.createdAt && (
                                <>
                                  {' · '}
                                  {t('rules.updatedAt')} <span className="font-mono">{formatRelativeTime(rule.updatedAt)}</span>
                                </>
                              )}
                            </p>
                            <PermissionGate allowed={canManage}>
                              <span className="flex items-center gap-2">
                                <Button variant="outline" size="sm" onClick={() => openEdit(rule)}>
                                  <Pencil className="h-3.5 w-3.5" /> {t('common.edit')}
                                </Button>
                                <Button variant="outline" size="sm" className="text-halt hover:text-halt" onClick={() => setDeleteId(rule.id)}>
                                  <Trash2 className="h-3.5 w-3.5" /> {t('common.delete')}
                                </Button>
                              </span>
                            </PermissionGate>
                          </div>
                        </div>
                      ) : undefined}
                    />
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}

      {/* Create / edit */}
      <Dialog open={showDialog} onOpenChange={setShowDialog}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{editing ? t('rules.editRule') : t('rules.createRule')}</DialogTitle>
            <DialogDescription>{t('rules.dialogDescription')}</DialogDescription>
          </DialogHeader>

          <div className="space-y-6 py-2">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5 sm:col-span-2">
                <Label htmlFor="rule-name">{t('rules.form.name')}</Label>
                <Input id="rule-name" value={formName} onChange={(e) => setFormName(e.target.value)} placeholder={t('rules.form.namePlaceholder')} />
              </div>
              <div className="space-y-1.5 sm:col-span-2">
                <Label htmlFor="rule-desc">{t('rules.form.description')}</Label>
                <Input id="rule-desc" value={formDescription} onChange={(e) => setFormDescription(e.target.value)} placeholder={t('rules.form.descriptionPlaceholder')} />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="rule-pattern">{t('rules.form.eventTypePattern')}</Label>
                <Input
                  id="rule-pattern"
                  value={formEventTypePattern}
                  onChange={(e) => setFormEventTypePattern(e.target.value)}
                  placeholder="order.*"
                  className="font-mono text-sm"
                />
                <p className="text-[11px] text-muted-foreground">{t('rules.form.patternHint')}</p>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="rule-priority">{t('rules.form.priority')}</Label>
                <Input
                  id="rule-priority"
                  type="number"
                  value={formPriority}
                  onChange={(e) => setFormPriority(parseInt(e.target.value) || 0)}
                  className="font-mono"
                />
                <p className="text-[11px] text-muted-foreground">{t('rules.form.priorityHint')}</p>
              </div>
            </div>

            <div className="flex items-center gap-3">
              <Switch id="rule-enabled" checked={formEnabled} onCheckedChange={setFormEnabled} />
              <Label htmlFor="rule-enabled" className="cursor-pointer">
                {formEnabled ? t('rules.form.enabled') : t('rules.form.disabled')}
              </Label>
            </div>

            {/* Conditions */}
            <div className="space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="flex items-center gap-2">
                  <Filter className="h-4 w-4 text-muted-foreground" aria-hidden />
                  <Label className="text-sm font-semibold">{t('rules.form.conditions')}</Label>
                </span>
                <span className="flex items-center gap-1.5">
                  <Button variant="outline" size="sm" onClick={addGroupToRoot}>
                    <FolderPlus className="h-3.5 w-3.5" /> {t('rules.form.addGroup')}
                  </Button>
                  <Button variant="outline" size="sm" onClick={addPredicateToRoot}>
                    <PlusCircle className="h-3.5 w-3.5" /> {t('rules.form.addCondition')}
                  </Button>
                </span>
              </div>

              {!formConditions || (formConditions.type === 'group' && formConditions.children.length === 0) ? (
                <EmptyState
                  icon={Filter}
                  title={t('rules.form.noConditions')}
                  className="flex flex-col items-center justify-center rounded-lg border border-dashed border-rail py-6"
                />
              ) : (
                <ConditionTreeEditor
                  node={formConditions}
                  path={[]}
                  onChange={setFormConditions}
                  onRemove={() => setFormConditions(null)}
                  depth={0}
                />
              )}
            </div>

            {/* Actions */}
            <div className="space-y-3">
              <span className="flex items-center gap-2">
                <Zap className="h-4 w-4 text-muted-foreground" aria-hidden />
                <Label className="text-sm font-semibold">{t('rules.form.actions')}</Label>
              </span>

              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {(Object.keys(ACTION_ICON) as ActionType[]).map((type) => {
                  const Icon = ACTION_ICON[type];
                  return (
                    <button
                      key={type}
                      type="button"
                      onClick={() => addAction(type)}
                      className="flex items-center gap-2 rounded-lg border border-dashed border-rail p-3 text-sm transition-colors hover:border-solid hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    >
                      <Icon className="h-4 w-4 text-muted-foreground" aria-hidden />
                      {t(`rules.actionTypes.${type}`)}
                    </button>
                  );
                })}
              </div>

              {formActions.length > 0 && (
                <ul className="space-y-2">
                  {formActions.map((action, idx) => {
                    const Icon = ACTION_ICON[action.type];
                    return (
                      <li key={idx} className="flex items-center gap-3 rounded-lg border border-rail bg-muted/40 p-3">
                        <Icon className="h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />
                        <div className="min-w-0 flex-1 space-y-1.5">
                          <p className="text-xs font-semibold">{t(`rules.actionTypes.${action.type}`)}</p>
                          {action.type === 'ROUTE' && (
                            <Select value={action.endpointId || ''} onChange={(e) => updateAction(idx, { endpointId: e.target.value || undefined })}>
                              <option value="">{t('rules.form.selectEndpoint')}</option>
                              {endpoints.map((ep) => <option key={ep.id} value={ep.id}>{ep.description || ep.url}</option>)}
                            </Select>
                          )}
                          {action.type === 'TRANSFORM' && (
                            <Select value={action.transformationId || ''} onChange={(e) => updateAction(idx, { transformationId: e.target.value || undefined })}>
                              <option value="">{t('rules.form.selectTransformation')}</option>
                              {transformations.map((tr) => <option key={tr.id} value={tr.id}>{tr.name}</option>)}
                            </Select>
                          )}
                          {action.type === 'TAG' && (
                            <Input
                              placeholder={t('rules.form.tagPlaceholder')}
                              value={(action.config?.tag as string) || ''}
                              onChange={(e) => updateAction(idx, { config: { ...action.config, tag: e.target.value } })}
                              className="text-xs"
                            />
                          )}
                          {action.type === 'DROP' && (
                            <p className="text-xs text-muted-foreground">{t('rules.form.dropHint')}</p>
                          )}
                        </div>
                        <Button variant="ghost" size="icon-sm" onClick={() => removeAction(idx)} aria-label={t('rules.form.removeAction')} className="text-muted-foreground hover:text-halt">
                          <X className="h-3.5 w-3.5" />
                        </Button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setShowDialog(false)}>{t('common.cancel')}</Button>
            <Button onClick={handleSave} disabled={!formName.trim() || createMutation.isPending || updateMutation.isPending}>
              {(createMutation.isPending || updateMutation.isPending) && <Loader2 className="h-4 w-4 animate-spin" />}
              {editing ? t('common.save') : t('rules.createRule')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete */}
      <AlertDialog open={!!deleteId} onOpenChange={(open) => !open && setDeleteId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('rules.deleteConfirm.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('rules.deleteConfirm.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className={buttonVariants({ variant: 'destructive' })}>
              {deleteMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('common.delete')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

// ── Read-only condition tree ───────────────────────────────────────

function ConditionTreeDisplay({ node }: { node: ConditionNode }) {
  if (node.type === 'predicate') {
    return (
      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-rail bg-card px-3 py-2 text-sm">
        <code className="font-mono text-xs text-primary">{node.field}</code>
        <Badge variant="outline" className="font-mono text-[10px]">{node.operator}</Badge>
        {!NO_VALUE_OPS.includes(node.operator) && (
          <code className="font-mono text-xs text-muted-foreground">{JSON.stringify(node.value)}</code>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-1.5 rounded-lg border-l-2 border-rail py-1.5 pl-3">
      <Badge variant="outline" className="font-mono text-[10px]">{node.op}</Badge>
      {node.children.map((child, i) => (
        <ConditionTreeDisplay key={i} node={child} />
      ))}
    </div>
  );
}
