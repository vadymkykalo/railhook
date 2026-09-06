import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Check, ExternalLink, Loader2, Minus, ShieldCheck } from 'lucide-react';
import { Card, CardContent } from '../components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import PageHeader from '../components/PageHeader';
import StatusBadge, { type StatusKind } from '../components/StatusBadge';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import { ErrorState } from '../components/EmptyState';
import { FormSection, SaveControl } from './SettingsPage';
import { billingApi, PlanResponse, ResourceUsage } from '../api/billing.api';
import { formatDate } from '../lib/date';
import { cn } from '../lib/utils';
import { showSuccess, showApiError } from '../lib/toast';
import DangerConfirmDialog from '../components/DangerConfirmDialog';

/** -1 is how the plan catalog spells "no ceiling". It must never reach a reader as "-1". */
const UNLIMITED = (n: number) => n < 0;

function useFormatters() {
  const { t, i18n } = useTranslation();
  const num = (n: number) => n.toLocaleString(i18n.language);
  /** A quota, or the word for having none. */
  const limit = (n: number) => (UNLIMITED(n) ? t('billing.unlimited') : num(n));
  const compact = (n: number) => {
    if (UNLIMITED(n)) return t('billing.unlimited');
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(0)}M`;
    if (n >= 1_000) return `${(n / 1_000).toFixed(0)}K`;
    return num(n);
  };
  const price = (cents: number) => `$${(cents / 100).toFixed(0)}`;
  return { num, limit, compact, price };
}

/**
 * How close this organization is to one of its ceilings. Nearing a limit is the
 * same kind of fact as an attempt still owed — it wants attention, not alarm —
 * so it takes the retry token; being over it takes halt.
 */
function kindOfUsage(percent: number): StatusKind {
  if (percent >= 100) return 'halt';
  if (percent >= 80) return 'retry';
  return 'ok';
}

const BAR_COLOR: Record<StatusKind, string> = {
  ok: 'bg-primary',
  retry: 'bg-retry',
  halt: 'bg-halt',
  idle: 'bg-idle',
};

function UsageMeter({ label, usage }: { label: string; usage: ResourceUsage }) {
  const { t } = useTranslation();
  const { num, limit } = useFormatters();
  const unlimited = UNLIMITED(usage.limit);
  const percent = unlimited ? 0 : Math.round(usage.percentUsed ?? (usage.limit ? (usage.current / usage.limit) * 100 : 0));
  const kind = kindOfUsage(percent);

  return (
    <div className="border-b border-rail py-3.5 last:border-b-0">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm font-medium">{label}</span>
        <span className="font-mono text-[13px] text-muted-foreground">
          {num(usage.current)}
          <span className="text-rail"> / </span>
          {limit(usage.limit)}
        </span>
      </div>
      {unlimited ? (
        <p className="mt-1.5 text-xs text-muted-foreground">{t('billing.noCeiling')}</p>
      ) : (
        <>
          <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-secondary">
            <div
              className={cn('h-full rounded-full transition-[width] duration-500', BAR_COLOR[kind])}
              style={{ width: `${Math.min(100, percent)}%` }}
              role="progressbar"
              aria-valuenow={percent}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label={label}
            />
          </div>
          <div className="mt-1.5 flex items-center gap-2">
            <span className="font-mono text-[11px] text-muted-foreground">{t('billing.used', { percent })}</span>
            {kind !== 'ok' && (
              <StatusBadge
                kind={kind}
                icon={false}
                label={t(kind === 'halt' ? 'billing.overLimit' : 'billing.nearLimit')}
              />
            )}
          </div>
        </>
      )}
    </div>
  );
}

function PlanLimits({ plan }: { plan: PlanResponse }) {
  const { t } = useTranslation();
  const { compact, limit } = useFormatters();
  const rows: [string, string][] = [
    [t('billing.events'), compact(plan.maxEventsPerMonth)],
    [t('billing.projects'), limit(plan.maxProjects)],
    [t('billing.endpoints'), limit(plan.maxEndpointsPerProject)],
    [t('billing.members'), limit(plan.maxMembers)],
    [t('billing.rateLimit'), UNLIMITED(plan.rateLimitPerSecond) ? t('billing.unlimited') : t('billing.perSecond', { count: plan.rateLimitPerSecond })],
    [t('billing.retention'), UNLIMITED(plan.maxRetentionDays) ? t('billing.unlimited') : t('billing.days', { count: plan.maxRetentionDays })],
    [t('billing.tunnels'), plan.maxActiveTunnels === 0 ? t('billing.tunnelsDisabled') : limit(plan.maxActiveTunnels)],
  ];
  return (
    <dl className="space-y-1">
      {rows.map(([k, v]) => (
        <div key={k} className="flex items-baseline justify-between gap-3 text-[12px]">
          <dt className="text-muted-foreground">{k}</dt>
          <dd className="font-mono text-foreground">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * The keys are the plan's `features` jsonb, spelled exactly as the seed and
 * every `@RequireFeature` spell them — `mTLS`, not `mtls`.
 *
 * `sso` used to be here, and V036 seeded it true on enterprise and self_hosted
 * with no SSO anywhere in the codebase, so this list showed a paying Enterprise
 * customer a feature that does not exist. V059 drops the flag; the row goes with
 * it. `tunnels` takes its place — that one is real, gated by
 * EntitlementService.checkTunnelLimit(), and now on for the free plan too.
 */
const FEATURE_KEYS = ['workflows', 'rules', 'replay', 'mTLS', 'tunnels'] as const;
const FEATURE_LABEL: Record<(typeof FEATURE_KEYS)[number], string> = {
  workflows: 'billing.featureWorkflows',
  rules: 'billing.featureRules',
  replay: 'billing.featureReplay',
  mTLS: 'billing.featureMtls',
  tunnels: 'billing.featureTunnels',
};

export default function BillingPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [confirmCancel, setConfirmCancel] = useState(false);
  const { num, price } = useFormatters();

  const [billingEmail, setBillingEmail] = useState('');
  const [emailDirty, setEmailDirty] = useState(false);
  const [emailSaved, setEmailSaved] = useState(false);
  const [annual, setAnnual] = useState(false);

  const billingQuery = useQuery({
    queryKey: ['billing', 'organization'],
    queryFn: billingApi.getOrganizationBilling,
  });
  const billing = billingQuery.data;

  const { data: usage } = useQuery({ queryKey: ['billing', 'usage'], queryFn: billingApi.getUsage });
  const { data: plans = [] } = useQuery({ queryKey: ['billing', 'plans'], queryFn: billingApi.listPlans });
  const { data: invoices = [] } = useQuery({ queryKey: ['billing', 'invoices'], queryFn: billingApi.listInvoices });

  useEffect(() => {
    if (billing && !emailDirty) setBillingEmail(billing.billingEmail || '');
  }, [billing, emailDirty]);

  const changePlanMutation = useMutation({
    mutationFn: (planName: string) => billingApi.changePlan({ planName }),
    onSuccess: () => {
      showSuccess(t('billing.changePlanSuccess'));
      queryClient.invalidateQueries({ queryKey: ['billing'] });
    },
    onError: (err) => showApiError(err, 'billing.changePlanFailed'),
  });

  const checkoutMutation = useMutation({
    mutationFn: ({ planName, interval }: { planName: string; interval: string }) =>
      billingApi.createCheckout({
        planName,
        billingInterval: interval,
        successUrl: `${window.location.origin}/admin/billing?checkout=success`,
        cancelUrl: `${window.location.origin}/admin/billing?checkout=cancel`,
      }),
    onSuccess: (data) => { if (data.url) window.location.href = data.url; },
    onError: (err) => showApiError(err, 'billing.checkoutFailed'),
  });

  // Both of these shipped in billing.api.ts and were never called, which left
  // billing a one-way door: an organization could start paying from here and
  // had nowhere in the product to stop.
  const portalMutation = useMutation({
    mutationFn: () => billingApi.createPortal(`${window.location.origin}/admin/billing`),
    onSuccess: (data) => { if (data.url) window.location.href = data.url; },
    onError: (err) => showApiError(err, 'billing.portalFailed'),
  });

  const cancelMutation = useMutation({
    mutationFn: () => billingApi.cancelSubscription(),
    onSuccess: () => {
      showSuccess(t('billing.cancelled'));
      setConfirmCancel(false);
      queryClient.invalidateQueries({ queryKey: ['billing'] });
    },
    onError: (err) => showApiError(err, 'billing.cancelFailed'),
  });

  const updateEmailMutation = useMutation({
    mutationFn: () => billingApi.updateBillingInfo({ billingEmail }),
    onSuccess: () => {
      showSuccess(t('billing.billingEmailUpdated'));
      setEmailDirty(false);
      setEmailSaved(true);
      queryClient.invalidateQueries({ queryKey: ['billing'] });
    },
    onError: (err) => showApiError(err, 'billing.billingEmailFailed'),
  });

  if (billingQuery.isLoading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={4} height="h-24" />
      </PageSkeleton>
    );
  }

  if (billingQuery.isError || !billing) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState
          error={billingQuery.error}
          fallbackKey="billing.loadFailed"
          onRetry={() => billingQuery.refetch()}
          retrying={billingQuery.isRefetching}
        />
      </div>
    );
  }

  const plan = billing.plan;
  const isSelfHosted = plan?.name === 'self_hosted';
  const features = plan?.features || {};
  const paid = !isSelfHosted && plan && plan.priceMonthlyCents > 0;
  const statusKind: StatusKind = billing.billingStatus === 'ACTIVE' ? 'ok' : 'halt';

  return (
    <div className="p-4 lg:p-6">
      <div className="max-w-4xl">
        <PageHeader
          eyebrow={usage ? `${formatDate(usage.periodStart)} — ${formatDate(usage.periodEnd)}` : undefined}
          title={t('billing.title')}
          description={t('billing.subtitle')}
        />

        <div className="space-y-8">
          {/* What this organization is on */}
          <section>
            <Card>
              <CardContent className="p-5">
                <div className="flex flex-wrap items-start justify-between gap-5">
                  <div className="min-w-0">
                    <p className="mono-label">{t('billing.currentPlan')}</p>
                    <div className="mt-1 flex flex-wrap items-baseline gap-3">
                      <h3 className="text-title">{plan?.displayName}</h3>
                      {isSelfHosted ? (
                        <span className="inline-flex items-center gap-1.5 text-sm text-muted-foreground">
                          <ShieldCheck className="h-4 w-4 text-primary" aria-hidden />
                          {t('billing.selfHosted')}
                        </span>
                      ) : paid ? (
                        <span className="font-mono text-sm text-muted-foreground">
                          {price(plan.priceMonthlyCents)}{t('billing.perMonth')}
                        </span>
                      ) : (
                        <span className="font-mono text-sm text-muted-foreground">{t('billing.free')}</span>
                      )}
                    </div>
                    {!isSelfHosted && billing.billingStatus && (
                      <div className="mt-3">
                        <StatusBadge kind={statusKind} label={t(`billing.statuses.${billing.billingStatus}`, { defaultValue: billing.billingStatus })} />
                      </div>
                    )}
                  </div>

                  <ul className="grid min-w-[13rem] gap-1.5">
                    {FEATURE_KEYS.map((key) => {
                      const included = isSelfHosted || features[key] === true;
                      return (
                        <li key={key} className="flex items-center gap-2 text-[13px]">
                          {included
                            ? <Check className="h-3.5 w-3.5 flex-shrink-0 text-primary" aria-hidden />
                            : <Minus className="h-3.5 w-3.5 flex-shrink-0 text-rail" aria-hidden />}
                          <span className={included ? '' : 'text-muted-foreground line-through decoration-rail'}>
                            {t(FEATURE_LABEL[key])}
                          </span>
                          <span className="sr-only">{t(included ? 'billing.included' : 'billing.notIncluded')}</span>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              </CardContent>
            </Card>
          </section>

          {/* How close it is to its ceilings */}
          {usage && (
            <FormSection title={t('billing.usage')} description={t('billing.usageSubtitle')}>
              <div>
                <UsageMeter label={t('billing.events')} usage={usage.events} />
                <UsageMeter label={t('billing.projects')} usage={usage.projects} />
                <UsageMeter label={t('billing.endpoints')} usage={usage.endpoints} />
                <UsageMeter label={t('billing.members')} usage={usage.members} />
              </div>
              <dl className="flex flex-wrap gap-x-8 gap-y-2 border-t border-rail pt-4">
                <div className="flex gap-2">
                  <dt className="mono-label">{t('billing.rateLimit')}</dt>
                  <dd className="font-mono text-[13px]">
                    {UNLIMITED(usage.rateLimitPerSecond) ? t('billing.unlimited') : t('billing.perSecond', { count: usage.rateLimitPerSecond })}
                  </dd>
                </div>
                <div className="flex gap-2">
                  <dt className="mono-label">{t('billing.retention')}</dt>
                  <dd className="font-mono text-[13px]">
                    {UNLIMITED(usage.retentionDays) ? t('billing.unlimited') : t('billing.days', { count: usage.retentionDays })}
                  </dd>
                </div>
              </dl>
            </FormSection>
          )}

          {/* What else it could be on */}
          {!isSelfHosted && plans.length > 0 && (
            <FormSection title={t('billing.availablePlans')} description={t('billing.availablePlansDesc')}>
              <div className="inline-flex items-center gap-1 rounded-lg border border-rail p-1" role="group" aria-label={t('billing.billingInterval')}>
                {([false, true] as const).map((yearly) => (
                  <button
                    key={String(yearly)}
                    type="button"
                    aria-pressed={annual === yearly}
                    onClick={() => setAnnual(yearly)}
                    className={cn(
                      'rounded-md px-3 py-1 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                      annual === yearly ? 'bg-secondary text-foreground' : 'text-muted-foreground hover:text-foreground'
                    )}
                  >
                    {t(yearly ? 'billing.yearly' : 'billing.monthly')}
                    {yearly && <span className="ml-1.5 font-mono text-[10px] text-primary">{t('billing.yearlySave')}</span>}
                  </button>
                ))}
              </div>

              <div className="grid gap-3 sm:grid-cols-2">
                {plans.map((p) => {
                  const isCurrent = plan?.name === p.name;
                  const shown = annual ? p.priceYearlyCents : p.priceMonthlyCents;
                  const isCustom = shown < 0;
                  const isPaid = shown > 0;
                  const busy = changePlanMutation.isPending || checkoutMutation.isPending;
                  return (
                    <div
                      key={p.id}
                      className={cn(
                        'rounded-lg border p-4',
                        isCurrent ? 'border-primary bg-accent/30' : 'border-rail bg-card'
                      )}
                    >
                      <div className="flex items-baseline justify-between gap-2">
                        <span className="text-sm font-medium">{p.displayName}</span>
                        {isCurrent && (
                          <span className="mono-label text-primary">{t('billing.currentBadge')}</span>
                        )}
                      </div>
                      <p className="mt-1 font-mono text-lg">
                        {isCustom
                          ? t('billing.custom')
                          : shown === 0
                            ? t('billing.free')
                            : <>{price(shown)}<span className="text-xs text-muted-foreground">{t(annual ? 'billing.perYear' : 'billing.perMonth')}</span></>}
                      </p>
                      <div className="mt-3 border-t border-rail pt-3">
                        <PlanLimits plan={p} />
                      </div>
                      {!isCurrent && (
                        <div className="mt-3">
                          {isCustom ? (
                            <Button asChild size="sm" variant="outline" className="w-full">
                              {/* Was a mailto to the author's personal Gmail, shipped inside
                                  the product to whoever clicked Enterprise. */}
                              <Link to="/contact">{t('billing.contactSales')}</Link>
                            </Button>
                          ) : (
                            <Button
                              size="sm"
                              variant={isPaid ? 'default' : 'outline'}
                              className="w-full"
                              disabled={busy}
                              onClick={() => isPaid
                                ? checkoutMutation.mutate({ planName: p.name, interval: annual ? 'YEARLY' : 'MONTHLY' })
                                : changePlanMutation.mutate(p.name)}
                            >
                              {busy && <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />}
                              {t(isPaid ? 'billing.upgrade' : 'billing.switchPlan', { plan: p.displayName })}
                            </Button>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </FormSection>
          )}

          {!isSelfHosted && (
            <FormSection
              title={t('billing.billingInfo')}
              description={t('billing.billingInfoDesc')}
              footer={
                <SaveControl
                  label={t('common.save')}
                  savingLabel={t('common.saving')}
                  saving={updateEmailMutation.isPending}
                  disabled={!emailDirty}
                  saved={emailSaved && !emailDirty}
                  onClick={() => updateEmailMutation.mutate()}
                />
              }
            >
              <div className="space-y-2">
                <Label htmlFor="billingEmail">{t('billing.billingEmail')}</Label>
                <Input
                  id="billingEmail"
                  type="email"
                  value={billingEmail}
                  onChange={(e) => { setBillingEmail(e.target.value); setEmailDirty(true); setEmailSaved(false); }}
                  placeholder={t('billing.billingEmailPlaceholder')}
                  className="max-w-sm"
                />
                <p className="text-xs text-muted-foreground">{t('billing.billingEmailHint')}</p>
              </div>
            </FormSection>
          )}

          {!isSelfHosted && paid && (
            <FormSection
              title={t('billing.manageSubscription')}
              description={t('billing.manageSubscriptionDesc')}
            >
              <div className="divide-y divide-rail">
                <div className="flex flex-wrap items-center justify-between gap-4 pb-4">
                  <div className="min-w-0">
                    <p className="text-sm font-medium">{t('billing.openPortal')}</p>
                    <p className="text-xs text-muted-foreground">{t('billing.openPortalHint')}</p>
                  </div>
                  <Button
                    variant="outline"
                    onClick={() => portalMutation.mutate()}
                    disabled={portalMutation.isPending}
                  >
                    {portalMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                    <ExternalLink className="h-4 w-4" />
                    {t('billing.openPortal')}
                  </Button>
                </div>
                <div className="flex flex-wrap items-center justify-between gap-4 pt-4">
                  <div className="min-w-0">
                    <p className="text-sm font-medium">{t('billing.cancel')}</p>
                    <p className="text-xs text-muted-foreground">{t('billing.cancelDesc')}</p>
                  </div>
                  <Button variant="outline" className="text-halt hover:bg-halt/10" onClick={() => setConfirmCancel(true)}>
                    {t('billing.cancel')}
                  </Button>
                </div>
              </div>
            </FormSection>
          )}

          {!isSelfHosted && (
            <FormSection title={t('billing.invoices')} description={t('billing.invoicesDesc')}>
              {invoices.length === 0 ? (
                <p className="rounded-lg border border-dashed border-rail px-4 py-8 text-center text-sm text-muted-foreground">
                  {t('billing.invoicesEmpty')}
                </p>
              ) : (
                <Card className="overflow-hidden">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{t('billing.invoiceDate')}</TableHead>
                        <TableHead>{t('billing.invoicePlan')}</TableHead>
                        <TableHead>{t('billing.invoiceAmount')}</TableHead>
                        <TableHead>{t('billing.invoiceStatus')}</TableHead>
                        <TableHead><span className="sr-only">{t('common.actions')}</span></TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {invoices.map((inv) => (
                        <TableRow key={inv.id}>
                          <TableCell className="whitespace-nowrap text-[13px] text-muted-foreground">
                            {inv.paidAt ? formatDate(inv.paidAt) : '—'}
                          </TableCell>
                          <TableCell className="text-[13px]">{inv.planName}</TableCell>
                          <TableCell className="whitespace-nowrap font-mono text-[13px]">
                            ${num(inv.amountCents / 100)} {inv.currency?.toUpperCase()}
                          </TableCell>
                          <TableCell>
                            <StatusBadge
                              kind={inv.status === 'paid' ? 'ok' : 'idle'}
                              icon={false}
                              label={t(`billing.invoiceStatuses.${inv.status}`, { defaultValue: inv.status })}
                            />
                          </TableCell>
                          <TableCell>
                            {inv.invoiceUrl && (
                              <a
                                href={inv.invoiceUrl}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="inline-flex items-center gap-1 text-[13px] text-primary hover:underline"
                              >
                                {t('billing.invoiceView')}
                                <ExternalLink className="h-3 w-3" aria-hidden />
                              </a>
                            )}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </Card>
              )}
            </FormSection>
          )}
        </div>
      </div>

      <DangerConfirmDialog
        open={confirmCancel}
        onOpenChange={setConfirmCancel}
        title={t('billing.cancelTitle')}
        description={t('billing.cancelBody')}
        confirmName={plan?.displayName ?? ''}
        impact={[t('billing.cancelImpact1'), t('billing.cancelImpact2'), t('billing.cancelImpact3')]}
        onConfirm={() => cancelMutation.mutate()}
        loading={cancelMutation.isPending}
        confirmLabel={t('billing.cancel')}
      />
    </div>
  );
}
