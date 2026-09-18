import { useTranslation } from 'react-i18next';
import { UserPlus } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import { SkeletonTable } from '../components/PageSkeleton';
import { Card } from '../components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { ChartCard, SERIES, TrendChart } from '../components/charts';
import { usePlatformOverview } from '../api/queries';
import type { PlatformActivation } from '../api/platformAdmin.api';
import { formatDateTimeCompact, formatNumber, formatRelativeTime } from '../lib/date';
import {
  EmailText, OrganizationLink, PLATFORM_TABLE, PLATFORM_TABLE_HEADER, PanelTitle, PlatformErrorState, PlatformScope, SignInMethods, VerifiedBadge,
} from './platformAdminParts';

function Kpi({ label, value, hint }: { label: string; value: number; hint?: string }) {
  return (
    <Card className="p-4">
      <p className="mono-label">{label}</p>
      <p className="mt-1.5 font-mono text-2xl font-medium tabular-nums">{formatNumber(value)}</p>
      {hint && <p className="mt-1 text-xs text-muted-foreground">{hint}</p>}
    </Card>
  );
}

/**
 * How far the last 30 days' sign-ups got. Two groups, because the later steps belong to the
 * organization rather than to whoever signed up: accounts and how many verified; organizations
 * and how many created a project, then sent an event. Each step is shown against the one before
 * it — that ratio is where people are lost — with a bar scaled to the first step of its group.
 */
function ActivationFunnel({ activation }: { activation: PlatformActivation }) {
  const { t } = useTranslation();
  const steps: { key: string; value: number; base: number; previous?: number }[] = [
    { key: 'signups', value: activation.signups, base: activation.signups },
    { key: 'verified', value: activation.verified, base: activation.signups, previous: activation.signups },
    { key: 'organizations', value: activation.organizations, base: activation.organizations },
    { key: 'withProject', value: activation.withProject, base: activation.organizations, previous: activation.organizations },
    { key: 'withEvent', value: activation.withEvent, base: activation.organizations, previous: activation.withProject },
  ];
  const title = t('platformAdmin.overview.activation.title');

  return (
    <Card className="p-5">
      <h3 className="text-sm font-medium leading-tight">{title}</h3>
      <p className="mt-1 text-xs text-muted-foreground">{t('platformAdmin.overview.activation.description')}</p>
      <ul aria-label={title} className="mt-4 grid gap-3">
        {steps.map((step) => {
          const width = step.base > 0 ? (step.value / step.base) * 100 : 0;
          const rate = step.previous !== undefined && step.previous > 0
            ? Math.round((step.value / step.previous) * 100)
            : undefined;
          return (
            <li key={step.key} className="grid grid-cols-[minmax(0,11rem)_1fr_auto] items-center gap-3 text-sm max-sm:grid-cols-[1fr_auto]">
              <span className="truncate text-muted-foreground">{t(`platformAdmin.overview.activation.${step.key}`)}</span>
              <span className="h-2 rounded-full bg-muted max-sm:order-last max-sm:col-span-2" aria-hidden="true">
                <span className="block h-full rounded-full" style={{ width: `${width}%`, backgroundColor: SERIES.brand }} />
              </span>
              <span className="whitespace-nowrap text-right font-mono tabular-nums">
                {formatNumber(step.value)}
                {rate !== undefined && <span className="ml-2 text-xs text-muted-foreground">{rate}%</span>}
              </span>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}

/** The deployment at a glance, and who arrived most recently. */
export default function PlatformOverviewPage() {
  const { t, i18n } = useTranslation();
  const { data, isLoading, isError, error, refetch, isRefetching } = usePlatformOverview();
  // TrendChart plots against `timestamp`; the days are UTC dates, so they are labelled in UTC too.
  const dailyPoints = (data?.daily30d ?? []).map((d) => ({ ...d, timestamp: d.date }));
  const formatDay = (date: string) =>
    new Date(`${date}T00:00:00Z`).toLocaleDateString(i18n.language, { month: 'short', day: 'numeric', timeZone: 'UTC' });

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={data
          ? t('platformAdmin.overview.generatedAt', { time: formatDateTimeCompact(data.generatedAt) })
          : t('platformAdmin.eyebrow')}
        description={t('platformAdmin.overview.description')}
      />
      <PlatformScope />

      {isError ? (
        <PlatformErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      ) : isLoading || !data ? (
        <Card className="overflow-hidden"><SkeletonTable rows={6} /></Card>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Kpi
              label={t('platformAdmin.overview.kpi.organizations')}
              value={data.organizations}
              hint={t('platformAdmin.overview.kpi.suspended', { count: data.suspendedOrganizations })}
            />
            <Kpi label={t('platformAdmin.overview.kpi.users')} value={data.users} />
            <Kpi
              label={t('platformAdmin.overview.kpi.signupsToday')}
              value={data.signupsToday}
              hint={t('platformAdmin.overview.kpi.signupsPeriods', { week: data.signups7d, month: data.signups30d })}
            />
            <Kpi
              label={t('platformAdmin.overview.kpi.eventsToday')}
              value={data.eventsToday}
              hint={t('platformAdmin.overview.kpi.events30d', { total: formatNumber(data.events30d) })}
            />
            <Kpi
              label={t('platformAdmin.overview.kpi.deliveriesSucceeded')}
              value={data.deliveriesSucceeded24h}
            />
            <Kpi
              label={t('platformAdmin.overview.kpi.deliveriesFailed')}
              value={data.deliveriesFailed24h}
              hint={t('platformAdmin.overview.kpi.deliveriesFailedHint')}
            />
            <Kpi label={t('platformAdmin.overview.kpi.activeTunnels')} value={data.activeTunnels} />
            <Kpi
              label={t('platformAdmin.overview.kpi.nearQuota')}
              value={data.organizationsNearQuota}
              hint={t('platformAdmin.overview.kpi.nearQuotaHint')}
            />
          </div>

          <div className="mt-6 grid gap-4 lg:grid-cols-3">
            <ActivationFunnel activation={data.activation30d} />
            <ChartCard
              title={t('platformAdmin.overview.daily.signups')}
              eyebrow={t('platformAdmin.overview.daily.period')}
              bodyClass="h-[220px]"
            >
              <TrendChart
                data={dailyPoints}
                dataKey="signups"
                seriesLabel={t('platformAdmin.overview.daily.signupsSeries')}
                formatTick={formatDay}
                formatStamp={formatDay}
                formatValue={formatNumber}
              />
            </ChartCard>
            <ChartCard
              title={t('platformAdmin.overview.daily.events')}
              eyebrow={t('platformAdmin.overview.daily.period')}
              bodyClass="h-[220px]"
            >
              <TrendChart
                data={dailyPoints}
                dataKey="events"
                seriesLabel={t('platformAdmin.overview.daily.eventsSeries')}
                formatTick={formatDay}
                formatStamp={formatDay}
                formatValue={formatNumber}
              />
            </ChartCard>
          </div>

          <Card className="mt-6 overflow-hidden">
            <PanelTitle title={t('platformAdmin.overview.recentSignups')} />
            {data.recentSignups.length === 0 ? (
              <EmptyState icon={UserPlus} title={t('platformAdmin.overview.noSignups')} className="rounded-none border-0 py-10" />
            ) : (
              <Table className={PLATFORM_TABLE}>
                <TableHeader className={PLATFORM_TABLE_HEADER}>
                  <TableRow>
                    <TableHead>{t('platformAdmin.columns.account')}</TableHead>
                    <TableHead>{t('platformAdmin.columns.organization')}</TableHead>
                    <TableHead>{t('platformAdmin.columns.signIn')}</TableHead>
                    <TableHead>{t('platformAdmin.columns.verified')}</TableHead>
                    <TableHead className="text-right">{t('platformAdmin.columns.created')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.recentSignups.map((signup) => (
                    <TableRow key={signup.userId}>
                      <TableCell>
                        <EmailText email={signup.email} />
                        {signup.fullName && <p className="max-w-[13rem] truncate text-xs text-muted-foreground max-sm:max-w-full" title={signup.fullName}>{signup.fullName}</p>}
                      </TableCell>
                      <TableCell>
                        {signup.organizationId && signup.organizationName
                          ? <OrganizationLink id={signup.organizationId} name={signup.organizationName} />
                          : '—'}
                      </TableCell>
                      <TableCell><SignInMethods methods={signup.signInMethods} /></TableCell>
                      <TableCell><VerifiedBadge verified={signup.emailVerified} /></TableCell>
                      <TableCell
                        className="whitespace-nowrap text-right font-mono text-xs text-muted-foreground"
                        title={formatDateTimeCompact(signup.createdAt)}
                      >
                        {formatRelativeTime(signup.createdAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
