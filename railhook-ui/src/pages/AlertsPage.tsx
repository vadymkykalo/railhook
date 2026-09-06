import { Fragment, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  Bell, BellOff, Check, ChevronDown, Clock, Loader2, Mail, MessageSquare, Plus, Search, Trash2,
  VolumeX, Webhook,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import {
  useAlertRules, useCreateAlertRule, useDeleteAlertRule, useUpdateAlertRule,
  useAlertEvents, useResolveAlert, useResolveAllAlerts, useUnresolvedAlertCount,
} from '../api/queries';
import type { AlertRuleRequest, AlertType, AlertSeverity, AlertChannel } from '../api/alerts.api';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { Button, buttonVariants } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import { Switch } from '../components/ui/switch';
import { Badge } from '../components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { cn } from '../lib/utils';
import { STATUS_FILL, STATUS_TEXT, StatTile, formatCompact, kindOfSeverity } from '../components/charts';

const ALERT_TYPE_VALUES: AlertType[] = ['FAILURE_RATE', 'DLQ_THRESHOLD', 'CONSECUTIVE_FAILURES', 'LATENCY_THRESHOLD'];
const SEVERITY_VALUES: AlertSeverity[] = ['INFO', 'WARNING', 'CRITICAL'];
const CHANNEL_VALUES: AlertChannel[] = ['IN_APP', 'EMAIL', 'WEBHOOK', 'SLACK'];
const SNOOZE_HOURS = [1, 4, 8, 24];

const CHANNEL_ICON: Record<AlertChannel, React.ElementType> = {
  IN_APP: Bell,
  EMAIL: Mail,
  WEBHOOK: Webhook,
  SLACK: MessageSquare,
};

/** True while a rule is actually armed — enabled, not muted, not snoozed. */
function isArmed(rule: { enabled: boolean; muted: boolean; snoozedUntil: string | null }): boolean {
  if (!rule.enabled || rule.muted) return false;
  return !rule.snoozedUntil || new Date(rule.snoozedUntil) <= new Date();
}

export default function AlertsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageEndpoints } = usePermissions();

  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [deleteRuleId, setDeleteRuleId] = useState<string | null>(null);
  const [eventsPage, setEventsPage] = useState(0);
  const [eventsPageSize, setEventsPageSize] = useState(20);
  const [expandedEventId, setExpandedEventId] = useState<string | null>(null);
  const [snoozeDropdownId, setSnoozeDropdownId] = useState<string | null>(null);

  const [formName, setFormName] = useState('');
  const [formType, setFormType] = useState<AlertType>('FAILURE_RATE');
  const [formSeverity, setFormSeverity] = useState<AlertSeverity>('WARNING');
  const [formThreshold, setFormThreshold] = useState('10');
  const [formWindow, setFormWindow] = useState('5');
  const [formDescription, setFormDescription] = useState('');
  const [formChannel, setFormChannel] = useState<AlertChannel>('IN_APP');
  const [formWebhookUrl, setFormWebhookUrl] = useState('');
  const [formEmailRecipients, setFormEmailRecipients] = useState('');

  const {
    data: rules = [], isLoading: rulesLoading, isError: rulesIsError, error: rulesError, refetch: refetchRules,
  } = useAlertRules(projectId);
  const {
    data: eventsData, isLoading: eventsLoading, isError: eventsIsError, error: eventsError, refetch: refetchEvents,
  } = useAlertEvents(projectId, eventsPage, eventsPageSize);
  const { data: unresolvedData } = useUnresolvedAlertCount(projectId);

  const createRule = useCreateAlertRule(projectId!);
  const deleteRule = useDeleteAlertRule(projectId!);
  const updateRule = useUpdateAlertRule(projectId!);
  const resolveAlert = useResolveAlert(projectId!);
  const resolveAll = useResolveAllAlerts(projectId!);

  const unresolvedCount = unresolvedData?.count ?? 0;
  const events = eventsData?.content ?? [];
  const armedCount = rules.filter(isArmed).length;
  const silencedCount = rules.length - armedCount;

  const resetForm = () => {
    setFormName('');
    setFormType('FAILURE_RATE');
    setFormSeverity('WARNING');
    setFormThreshold('10');
    setFormWindow('5');
    setFormDescription('');
    setFormChannel('IN_APP');
    setFormWebhookUrl('');
    setFormEmailRecipients('');
  };

  const handleCreate = async () => {
    const data: AlertRuleRequest = {
      name: formName,
      alertType: formType,
      severity: formSeverity,
      thresholdValue: parseFloat(formThreshold),
      windowMinutes: parseInt(formWindow),
      description: formDescription || undefined,
      channel: formChannel,
      webhookUrl: (formChannel === 'WEBHOOK' || formChannel === 'SLACK') ? formWebhookUrl : undefined,
      emailRecipients: formChannel === 'EMAIL' ? formEmailRecipients : undefined,
    };
    try {
      await createRule.mutateAsync(data);
      showSuccess(t('alerts.toast.ruleCreated'));
      setShowCreateDialog(false);
      resetForm();
    } catch (err: any) {
      showApiError(err, 'alerts.toast.createFailed');
    }
  };

  const handleDelete = async () => {
    if (!deleteRuleId) return;
    try {
      await deleteRule.mutateAsync(deleteRuleId);
      showSuccess(t('alerts.toast.ruleDeleted'));
    } catch (err: any) {
      showApiError(err, 'alerts.toast.deleteFailed');
    } finally {
      setDeleteRuleId(null);
    }
  };

  const handleToggleRule = async (ruleId: string, enabled: boolean) => {
    try {
      await updateRule.mutateAsync({ ruleId, data: { enabled } });
    } catch (err: any) {
      showApiError(err, 'alerts.toast.updateFailed');
    }
  };

  const handleMuteRule = async (ruleId: string, muted: boolean) => {
    try {
      await updateRule.mutateAsync({ ruleId, data: { muted } });
      showSuccess(muted ? t('alerts.toast.muted') : t('alerts.toast.unmuted'));
    } catch (err: any) {
      showApiError(err, 'alerts.toast.updateFailed');
    }
  };

  const handleSnoozeRule = async (ruleId: string, hours: number) => {
    const snoozedUntil = new Date(Date.now() + hours * 3600000).toISOString();
    try {
      await updateRule.mutateAsync({ ruleId, data: { snoozedUntil } });
      showSuccess(t('alerts.toast.snoozed', { hours }));
    } catch (err: any) {
      showApiError(err, 'alerts.toast.updateFailed');
    }
  };

  const handleResolve = async (eventId: string) => {
    try {
      await resolveAlert.mutateAsync(eventId);
    } catch (err: any) {
      showApiError(err, 'alerts.toast.resolveFailed');
    }
  };

  const handleResolveAll = async () => {
    try {
      const result = await resolveAll.mutateAsync();
      showSuccess(t('alerts.toast.allResolved', { count: result.resolved }));
    } catch (err: any) {
      showApiError(err, 'alerts.toast.resolveFailed');
    }
  };

  if (rulesLoading && eventsLoading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={3} height="h-[104px]" cols="grid-cols-1 lg:grid-cols-3" />
        <SkeletonCards count={1} height="h-[320px]" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        title={t('alerts.title')}
        description={t('alerts.subtitle')}
        actions={
          <>
            {unresolvedCount > 0 && (
              <PermissionGate allowed={canManageEndpoints}>
                <VerificationGate>
                  <Button variant="outline" size="sm" onClick={handleResolveAll} disabled={resolveAll.isPending}>
                    <Check className="h-4 w-4" /> {t('alerts.resolveAll')}
                  </Button>
                </VerificationGate>
              </PermissionGate>
            )}
            <PermissionGate allowed={canManageEndpoints}>
              <VerificationGate>
                <Button onClick={() => setShowCreateDialog(true)}>
                  <Plus className="h-4 w-4" /> {t('alerts.createRule')}
                </Button>
              </VerificationGate>
            </PermissionGate>
          </>
        }
      />

      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-3">
          <StatTile
            label={t('alerts.tiles.unresolved')}
            value={formatCompact(unresolvedCount)}
            hint={t('alerts.tiles.unresolvedHint')}
            badge={unresolvedCount > 0
              ? <StatusBadge kind="halt" label={t('alerts.active')} icon={false} />
              : <StatusBadge kind="ok" label={t('alerts.tiles.allClear')} icon={false} />}
          />
          <StatTile
            label={t('alerts.tiles.armed')}
            value={formatCompact(armedCount)}
            hint={t('alerts.tiles.armedHint', { total: rules.length })}
          />
          <StatTile
            label={t('alerts.tiles.silenced')}
            value={formatCompact(silencedCount)}
            hint={t('alerts.tiles.silencedHint')}
            badge={silencedCount > 0 ? <StatusBadge kind="idle" label={t('alerts.muted')} icon={false} /> : undefined}
          />
        </div>

        {/* What fired — the surface that needs a human. */}
        <section>
          <div className="mb-3">
            <h3 className="text-sm font-medium leading-tight">{t('alerts.history.title')}</h3>
            <p className="mt-0.5 text-xs text-muted-foreground">{t('alerts.history.desc')}</p>
          </div>

          {eventsIsError ? (
            <ErrorState error={eventsError} fallbackKey="alerts.loadFailed" onRetry={() => refetchEvents()} />
          ) : events.length === 0 ? (
            <EmptyState icon={Bell} title={t('alerts.noEvents')} description={t('alerts.noEventsDesc')} />
          ) : (
            <div className="animate-fade-in">
              <Card className="overflow-hidden">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[112px]">{t('alerts.columns.severity')}</TableHead>
                      <TableHead>{t('alerts.columns.title')}</TableHead>
                      <TableHead className="w-[150px]">{t('alerts.columns.value')}</TableHead>
                      <TableHead className="w-[150px]">{t('alerts.columns.time')}</TableHead>
                      <TableHead className="w-[110px]">{t('alerts.columns.status')}</TableHead>
                      <TableHead className="w-[48px]"><span className="sr-only">{t('alerts.columns.details')}</span></TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {events.map((event) => {
                      const kind = kindOfSeverity(event.severity);
                      const isExpanded = expandedEventId === event.id;
                      const rule = rules.find((r) => r.id === event.alertRuleId);
                      const investigateUrl = projectId
                        ? `/admin/projects/${projectId}/deliveries?status=FAILED${rule?.endpointId ? `&endpointId=${rule.endpointId}` : ''}`
                        : null;
                      const overshoot = event.currentValue != null && event.thresholdValue
                        ? Math.min((event.currentValue / event.thresholdValue) * 100, 100)
                        : 0;
                      return (
                        <Fragment key={event.id}>
                          <TableRow
                            className={cn('cursor-pointer', event.resolved && 'opacity-60', isExpanded && 'bg-secondary/40')}
                            onClick={() => setExpandedEventId(isExpanded ? null : event.id)}
                          >
                            <TableCell>
                              <StatusBadge kind={kind} label={t(`alerts.severities.${event.severity}`)} />
                            </TableCell>
                            <TableCell className="max-w-0">
                              <p className="truncate text-sm font-medium">{event.title}</p>
                              {event.message && !isExpanded && (
                                <p className="mt-0.5 truncate text-xs text-muted-foreground">{event.message}</p>
                              )}
                            </TableCell>
                            <TableCell>
                              {event.currentValue != null && event.thresholdValue != null ? (
                                <>
                                  <span className="font-mono text-xs tabular-nums">
                                    <span className={STATUS_TEXT[kind]}>{event.currentValue.toFixed(1)}</span>
                                    <span className="text-muted-foreground"> / {event.thresholdValue.toFixed(1)}</span>
                                  </span>
                                  <span className="relative mt-1 block h-1 w-full overflow-hidden rounded-full bg-muted">
                                    <span
                                      className={cn('absolute inset-y-0 left-0 rounded-full', STATUS_FILL[kind])}
                                      style={{ width: `${overshoot}%` }}
                                    />
                                  </span>
                                </>
                              ) : (
                                <span className="font-mono text-xs text-muted-foreground">—</span>
                              )}
                            </TableCell>
                            <TableCell>
                              <span className="block text-sm">{formatRelativeTime(event.createdAt)}</span>
                              <span className="block font-mono text-[11px] text-muted-foreground">
                                {formatDateTime(event.createdAt)}
                              </span>
                            </TableCell>
                            <TableCell>
                              <StatusBadge
                                kind={event.resolved ? 'ok' : 'halt'}
                                label={t(event.resolved ? 'alerts.resolved' : 'alerts.active')}
                                icon={false}
                              />
                            </TableCell>
                            <TableCell>
                              <ChevronDown
                                className={cn('h-3.5 w-3.5 text-muted-foreground transition-transform', isExpanded && 'rotate-180')}
                                aria-hidden
                              />
                            </TableCell>
                          </TableRow>
                          {isExpanded && (
                            <TableRow className="bg-secondary/20 hover:bg-secondary/20">
                              <TableCell colSpan={6} className="py-3">
                                <div className="space-y-3 pl-2">
                                  {event.message && <p className="text-sm text-muted-foreground">{event.message}</p>}
                                  {rule && (
                                    <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                                      <span>{t('alerts.triggeredBy')}</span>
                                      <Badge variant="secondary">{rule.name}</Badge>
                                      <Badge variant="outline">{t(`alerts.types.${rule.alertType}.label`)}</Badge>
                                    </div>
                                  )}
                                  {event.resolvedAt && (
                                    <p className="text-xs text-muted-foreground">
                                      {t('alerts.resolvedAtLabel', { time: formatDateTime(event.resolvedAt) })}
                                    </p>
                                  )}
                                  <div className="flex flex-wrap items-center gap-2">
                                    {investigateUrl && (
                                      <Button variant="outline" size="sm" asChild>
                                        <Link to={investigateUrl}>
                                          <Search className="h-3.5 w-3.5" />
                                          {t('alerts.investigate')}
                                        </Link>
                                      </Button>
                                    )}
                                    {!event.resolved && canManageEndpoints && (
                                      <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={(e) => { e.stopPropagation(); handleResolve(event.id); }}
                                      >
                                        <Check className="h-3.5 w-3.5" />
                                        {t('alerts.resolve')}
                                      </Button>
                                    )}
                                  </div>
                                </div>
                              </TableCell>
                            </TableRow>
                          )}
                        </Fragment>
                      );
                    })}
                  </TableBody>
                </Table>
              </Card>
              <TablePagination
                page={eventsPage}
                pageSize={eventsPageSize}
                totalElements={eventsData?.totalElements ?? 0}
                totalPages={eventsData?.totalPages ?? 0}
                onPageChange={setEventsPage}
                onPageSizeChange={setEventsPageSize}
              />
            </div>
          )}
        </section>

        {/* What is watching. */}
        <section>
          <div className="mb-3">
            <h3 className="text-sm font-medium leading-tight">{t('alerts.rules.title')}</h3>
            <p className="mt-0.5 text-xs text-muted-foreground">{t('alerts.rules.desc')}</p>
          </div>

          {rulesIsError ? (
            <ErrorState error={rulesError} fallbackKey="alerts.loadFailed" onRetry={() => refetchRules()} />
          ) : rules.length === 0 ? (
            <EmptyState
              icon={Bell}
              title={t('alerts.noRules')}
              description={t('alerts.noRulesDesc')}
              action={
                <PermissionGate allowed={canManageEndpoints}>
                  <VerificationGate>
                    <Button onClick={() => setShowCreateDialog(true)}>
                      <Plus className="h-4 w-4" /> {t('alerts.createRule')}
                    </Button>
                  </VerificationGate>
                </PermissionGate>
              }
            />
          ) : (
            <div className="grid animate-fade-in gap-4 md:grid-cols-2">
              {rules.map((rule) => {
                const ChannelIcon = CHANNEL_ICON[rule.channel] ?? Bell;
                const armed = isArmed(rule);
                const snoozed = !!rule.snoozedUntil && new Date(rule.snoozedUntil) > new Date();
                return (
                  <Card key={rule.id} className="p-5">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <h4 className="truncate text-sm font-medium">{rule.name}</h4>
                        {rule.description && (
                          <p className="mt-0.5 text-xs text-muted-foreground">{rule.description}</p>
                        )}
                      </div>
                      <Switch
                        checked={rule.enabled}
                        onCheckedChange={(v) => handleToggleRule(rule.id, v)}
                        aria-label={t('alerts.toggleRule', { name: rule.name })}
                      />
                    </div>

                    <div className="mt-3 flex flex-wrap items-center gap-2">
                      <StatusBadge
                        kind={armed ? kindOfSeverity(rule.severity) : 'idle'}
                        label={t(`alerts.severities.${rule.severity}`)}
                        icon={false}
                      />
                      <Badge variant="outline">{t(`alerts.types.${rule.alertType}.label`)}</Badge>
                      <Badge variant="secondary">
                        <ChannelIcon className="h-3 w-3" aria-hidden />
                        {t(`alerts.channels.${rule.channel}`)}
                      </Badge>
                      {rule.muted && (
                        <Badge variant="outline"><VolumeX className="h-3 w-3" aria-hidden />{t('alerts.muted')}</Badge>
                      )}
                      {snoozed && (
                        <Badge variant="outline"><BellOff className="h-3 w-3" aria-hidden />{t('alerts.snoozed')}</Badge>
                      )}
                    </div>

                    <dl className="mt-3 grid grid-cols-2 gap-2 text-xs">
                      <div className="flex items-baseline gap-1.5">
                        <dt className="text-muted-foreground">{t('alerts.threshold')}</dt>
                        <dd className="font-mono tabular-nums">{rule.thresholdValue}</dd>
                      </div>
                      <div className="flex items-baseline gap-1.5">
                        <dt className="text-muted-foreground">{t('alerts.window')}</dt>
                        <dd className="font-mono tabular-nums">{t('alerts.windowMinutes', { count: rule.windowMinutes })}</dd>
                      </div>
                    </dl>

                    {(rule.channel === 'WEBHOOK' || rule.channel === 'SLACK') && rule.webhookUrl && (
                      <p className="mt-2 truncate font-mono text-[11px] text-muted-foreground">{rule.webhookUrl}</p>
                    )}
                    {rule.channel === 'EMAIL' && rule.emailRecipients && (
                      <p className="mt-2 truncate font-mono text-[11px] text-muted-foreground">{rule.emailRecipients}</p>
                    )}

                    <div className="mt-4 flex items-center justify-between border-t border-rail pt-3">
                      <span className="text-[11px] text-muted-foreground">{formatRelativeTime(rule.createdAt)}</span>
                      {canManageEndpoints && (
                        <div className="flex items-center gap-1">
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={() => handleMuteRule(rule.id, !rule.muted)}
                            aria-label={t(rule.muted ? 'alerts.unmute' : 'alerts.mute')}
                          >
                            <VolumeX className={cn('h-3.5 w-3.5', rule.muted && 'text-muted-foreground')} />
                          </Button>
                          <div className="relative">
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              onClick={() => setSnoozeDropdownId(snoozeDropdownId === rule.id ? null : rule.id)}
                              aria-label={t('alerts.snooze')}
                              aria-expanded={snoozeDropdownId === rule.id}
                            >
                              <Clock className="h-3.5 w-3.5" />
                            </Button>
                            {snoozeDropdownId === rule.id && (
                              <div className="absolute right-0 top-full z-10 mt-1 min-w-[130px] rounded-md border border-rail bg-popover py-1 shadow-elevated">
                                {SNOOZE_HOURS.map((h) => (
                                  <button
                                    key={h}
                                    type="button"
                                    className="w-full px-3 py-1.5 text-left text-xs transition-colors hover:bg-secondary"
                                    onClick={() => { handleSnoozeRule(rule.id, h); setSnoozeDropdownId(null); }}
                                  >
                                    {t('alerts.snoozeFor', { hours: h })}
                                  </button>
                                ))}
                              </div>
                            )}
                          </div>
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            className="text-halt"
                            onClick={() => setDeleteRuleId(rule.id)}
                            aria-label={t('alerts.deleteRule')}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      )}
                    </div>
                  </Card>
                );
              })}
            </div>
          )}
        </section>
      </div>

      <Dialog open={showCreateDialog} onOpenChange={setShowCreateDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('alerts.createDialog.title')}</DialogTitle>
            <DialogDescription>{t('alerts.createDialog.description')}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label htmlFor="alert-name">{t('alerts.form.name')}</Label>
              <Input
                id="alert-name"
                value={formName}
                onChange={(e) => setFormName(e.target.value)}
                placeholder={t('alerts.form.namePlaceholder')}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="alert-type">{t('alerts.form.type')}</Label>
              <Select id="alert-type" value={formType} onChange={(e) => setFormType(e.target.value as AlertType)}>
                {ALERT_TYPE_VALUES.map((v) => <option key={v} value={v}>{t(`alerts.types.${v}.label`)}</option>)}
              </Select>
              <p className="text-xs text-muted-foreground">{t(`alerts.types.${formType}.hint`)}</p>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="alert-threshold">{t('alerts.form.threshold')}</Label>
                <Input id="alert-threshold" type="number" value={formThreshold} onChange={(e) => setFormThreshold(e.target.value)} min="0" step="0.1" />
              </div>
              <div className="space-y-2">
                <Label htmlFor="alert-window">{t('alerts.form.window')}</Label>
                <Input id="alert-window" type="number" value={formWindow} onChange={(e) => setFormWindow(e.target.value)} min="1" max="1440" />
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="alert-severity">{t('alerts.form.severity')}</Label>
              <Select id="alert-severity" value={formSeverity} onChange={(e) => setFormSeverity(e.target.value as AlertSeverity)}>
                {SEVERITY_VALUES.map((v) => <option key={v} value={v}>{t(`alerts.severities.${v}`)}</option>)}
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="alert-description">{t('alerts.form.description')}</Label>
              <Input
                id="alert-description"
                value={formDescription}
                onChange={(e) => setFormDescription(e.target.value)}
                placeholder={t('alerts.form.descriptionPlaceholder')}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="alert-channel">{t('alerts.form.channel')}</Label>
              <Select id="alert-channel" value={formChannel} onChange={(e) => setFormChannel(e.target.value as AlertChannel)}>
                {CHANNEL_VALUES.map((v) => <option key={v} value={v}>{t(`alerts.channels.${v}`)}</option>)}
              </Select>
            </div>
            {formChannel === 'WEBHOOK' && (
              <div className="space-y-2">
                <Label htmlFor="alert-webhook">{t('alerts.form.webhookUrl')}</Label>
                <Input id="alert-webhook" value={formWebhookUrl} onChange={(e) => setFormWebhookUrl(e.target.value)} placeholder="https://example.com/webhook" />
              </div>
            )}
            {formChannel === 'SLACK' && (
              <div className="space-y-2">
                <Label htmlFor="alert-slack">{t('alerts.form.slackWebhookUrl')}</Label>
                <Input id="alert-slack" value={formWebhookUrl} onChange={(e) => setFormWebhookUrl(e.target.value)} placeholder="https://hooks.slack.com/services/..." />
                <p className="text-xs text-muted-foreground">{t('alerts.form.slackHint')}</p>
              </div>
            )}
            {formChannel === 'EMAIL' && (
              <div className="space-y-2">
                <Label htmlFor="alert-email">{t('alerts.form.emailRecipients')}</Label>
                <Input id="alert-email" value={formEmailRecipients} onChange={(e) => setFormEmailRecipients(e.target.value)} placeholder="ops@company.com, dev@company.com" />
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowCreateDialog(false)}>{t('common.cancel')}</Button>
            <Button onClick={handleCreate} disabled={!formName || !formThreshold || createRule.isPending}>
              {createRule.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('alerts.createDialog.submit')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteRuleId} onOpenChange={() => setDeleteRuleId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('alerts.deleteDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('alerts.deleteDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className={buttonVariants({ variant: 'destructive' })}>
              {t('common.delete')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
