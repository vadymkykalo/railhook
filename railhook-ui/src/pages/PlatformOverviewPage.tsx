import { useTranslation } from 'react-i18next';
import { UserPlus } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import { SkeletonTable } from '../components/PageSkeleton';
import { Card } from '../components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { usePlatformOverview } from '../api/queries';
import { formatDateTimeCompact, formatNumber, formatRelativeTime } from '../lib/date';
import {
  EmailText, OrganizationLink, PLATFORM_TABLE_HEADER, PanelTitle, PlatformErrorState, PlatformScope, SignInMethods, VerifiedBadge,
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

/** The deployment at a glance, and who arrived most recently. */
export default function PlatformOverviewPage() {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch, isRefetching } = usePlatformOverview();

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

          <Card className="mt-6 overflow-hidden">
            <PanelTitle title={t('platformAdmin.overview.recentSignups')} />
            {data.recentSignups.length === 0 ? (
              <EmptyState icon={UserPlus} title={t('platformAdmin.overview.noSignups')} className="rounded-none border-0 py-10" />
            ) : (
              <Table>
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
                        {signup.fullName && <p className="max-w-[15rem] truncate text-xs text-muted-foreground max-sm:max-w-full" title={signup.fullName}>{signup.fullName}</p>}
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
