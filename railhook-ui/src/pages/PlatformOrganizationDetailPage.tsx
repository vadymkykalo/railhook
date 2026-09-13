import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Ban, FileText, FolderOpen, ShieldCheck } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { SkeletonTable } from '../components/PageSkeleton';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import {
  usePlatformOrganization,
  usePlatformOrganizationAuditLog,
  usePlatformOrganizationMembers,
  usePlatformOrganizationProjects,
  usePlatformOrganizationUsage,
} from '../api/queries';
import type { AdminResourceUsage } from '../api/platformAdmin.api';
import { formatDate, formatDateTimeCompact, formatNumber, formatRelativeTime } from '../lib/date';
import { cn } from '../lib/utils';
import {
  OrganizationStatusBadge, PanelTitle, PlatformAdminBadge, PlatformErrorState, SignInMethods, SuspensionDialog, VerifiedBadge,
} from './platformAdminParts';

function BackLink() {
  const { t } = useTranslation();
  return (
    <Link
      to="/admin/platform/organizations"
      className="mb-3 inline-flex min-h-10 items-center gap-1.5 text-[13px] text-muted-foreground hover:text-foreground"
    >
      <ArrowLeft className="h-3.5 w-3.5" aria-hidden /> {t('platformAdmin.detail.back')}
    </Link>
  );
}

/** One resource against the plan. No limit (billing off, self_hosted) reads as Unlimited, with no bar. */
function UsageRow({ label, usage }: { label: string; usage: AdminResourceUsage }) {
  const { t } = useTranslation();
  const unlimited = usage.limit <= 0;
  const percent = unlimited ? 0 : Math.min(100, usage.percentUsed);
  return (
    <div>
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span>{label}</span>
        <span className="font-mono text-[13px] text-muted-foreground">
          {unlimited
            ? t('platformAdmin.detail.usageUnlimited', { current: formatNumber(usage.current) })
            : t('platformAdmin.detail.usageOf', { current: formatNumber(usage.current), limit: formatNumber(usage.limit) })}
        </span>
      </div>
      {!unlimited && (
        <div className="mt-1.5 h-1.5 rounded-full bg-secondary" aria-hidden>
          <div
            className={cn('h-1.5 rounded-full', percent >= 100 ? 'bg-halt' : percent >= 80 ? 'bg-retry' : 'bg-primary')}
            style={{ width: `${percent}%` }}
          />
        </div>
      )}
    </div>
  );
}

/** One organization: who is in it, what it has, what it has used, what happened, and suspension. */
export default function PlatformOrganizationDetailPage() {
  const { t } = useTranslation();
  const { organizationId = '' } = useParams();
  const organization = usePlatformOrganization(organizationId);
  const usage = usePlatformOrganizationUsage(organizationId);
  const [membersPage, setMembersPage] = useState(0);
  const [membersSize, setMembersSize] = useState(20);
  const members = usePlatformOrganizationMembers(organizationId, membersPage, membersSize);
  const projects = usePlatformOrganizationProjects(organizationId);
  const [auditPage, setAuditPage] = useState(0);
  const [auditSize, setAuditSize] = useState(20);
  const audit = usePlatformOrganizationAuditLog(organizationId, auditPage, auditSize);
  const [dialog, setDialog] = useState<'suspend' | 'reinstate' | null>(null);

  if (organization.isError) {
    return (
      <div className="p-4 lg:p-6">
        <BackLink />
        <PlatformErrorState
          error={organization.error}
          onRetry={() => organization.refetch()}
          retrying={organization.isRefetching}
        />
      </div>
    );
  }
  if (organization.isLoading || !organization.data) {
    return (
      <div className="p-4 lg:p-6">
        <BackLink />
        <Card className="overflow-hidden"><SkeletonTable rows={6} /></Card>
      </div>
    );
  }

  const org = organization.data;
  const suspended = !!org.suspendedAt;

  return (
    <div className="p-4 lg:p-6">
      <BackLink />
      <PageHeader
        eyebrow={t('platformAdmin.detail.eyebrow', { plan: org.planName ?? '—', created: formatDate(org.createdAt) })}
        title={org.name}
        description={(
          <span className="flex flex-wrap items-center gap-2">
            <OrganizationStatusBadge organization={org} />
            <span className="break-all font-mono text-[13px]">
              {org.ownerEmail
                ? t('platformAdmin.detail.owner', { email: org.ownerEmail })
                : t('platformAdmin.detail.noOwner')}
            </span>
          </span>
        )}
        actions={suspended ? (
          <Button onClick={() => setDialog('reinstate')} className="max-sm:w-full">
            <ShieldCheck className="h-4 w-4" aria-hidden /> {t('platformAdmin.detail.reinstate')}
          </Button>
        ) : (
          <Button variant="outline" onClick={() => setDialog('suspend')} className="text-halt max-sm:w-full">
            <Ban className="h-4 w-4" aria-hidden /> {t('platformAdmin.detail.suspend')}
          </Button>
        )}
      />

      {suspended && (
        <div role="status" className="mb-5 rounded-lg border border-halt/30 bg-halt-soft p-3 text-sm">
          <p className="font-medium text-halt">
            {t('platformAdmin.detail.suspendedSince', {
              time: formatDateTimeCompact(org.suspendedAt!),
              by: org.suspendedBy ?? '—',
            })}
          </p>
          {org.suspensionReason && <p className="mt-1 break-words text-muted-foreground">{org.suspensionReason}</p>}
        </div>
      )}

      <div className="grid gap-5 xl:grid-cols-3">
        <Card className="overflow-hidden">
          <PanelTitle
            title={t('platformAdmin.detail.usage')}
            meta={usage.data ? formatDate(usage.data.periodStart) : undefined}
          />
          <div className="space-y-4 p-4">
            {usage.isError ? (
              <PlatformErrorState error={usage.error} onRetry={() => usage.refetch()} />
            ) : !usage.data ? (
              <SkeletonTable rows={4} />
            ) : (
              <>
                <UsageRow label={t('platformAdmin.detail.usageEvents')} usage={usage.data.events} />
                <UsageRow label={t('platformAdmin.detail.usageProjects')} usage={usage.data.projects} />
                <UsageRow label={t('platformAdmin.detail.usageEndpoints')} usage={usage.data.endpoints} />
                <UsageRow label={t('platformAdmin.detail.usageMembers')} usage={usage.data.members} />
              </>
            )}
          </div>
        </Card>

        <Card className="overflow-hidden xl:col-span-2">
          <PanelTitle title={t('platformAdmin.detail.members')} meta={members.data?.totalElements} />
          {members.isError ? (
            <PlatformErrorState error={members.error} onRetry={() => members.refetch()} />
          ) : !members.data ? (
            <SkeletonTable rows={4} />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('platformAdmin.columns.account')}</TableHead>
                  <TableHead>{t('platformAdmin.columns.role')}</TableHead>
                  <TableHead>{t('platformAdmin.columns.membership')}</TableHead>
                  <TableHead>{t('platformAdmin.columns.verified')}</TableHead>
                  <TableHead>{t('platformAdmin.columns.signIn')}</TableHead>
                  <TableHead className="text-right">{t('platformAdmin.columns.lastSeen')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {members.data.content.map((member) => (
                  <TableRow key={member.userId}>
                    <TableCell className="max-w-[16rem]">
                      <p className="truncate font-mono text-[13px]" title={member.email ?? undefined}>{member.email ?? '—'}</p>
                      {member.fullName && <p className="truncate text-xs text-muted-foreground">{member.fullName}</p>}
                      {member.platformAdmin && <div className="mt-1"><PlatformAdminBadge /></div>}
                    </TableCell>
                    <TableCell className="text-[13px]">{t(`members.roles.${member.role}`, { defaultValue: member.role })}</TableCell>
                    <TableCell>
                      <StatusBadge
                        kind={member.membershipStatus === 'ACTIVE' ? 'ok' : member.membershipStatus === 'DISABLED' ? 'halt' : 'idle'}
                        label={t(`members.statuses.${member.membershipStatus}`, { defaultValue: member.membershipStatus })}
                      />
                    </TableCell>
                    <TableCell><VerifiedBadge verified={member.emailVerified} /></TableCell>
                    <TableCell><SignInMethods methods={member.signInMethods} /></TableCell>
                    <TableCell className="whitespace-nowrap text-right font-mono text-xs text-muted-foreground">
                      {member.lastSeenAt ? formatRelativeTime(member.lastSeenAt) : '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {members.data && members.data.totalElements > membersSize && (
            <div className="px-4 pb-4">
              <TablePagination
                page={membersPage}
                pageSize={membersSize}
                totalElements={members.data.totalElements}
                totalPages={members.data.totalPages}
                onPageChange={setMembersPage}
                onPageSizeChange={setMembersSize}
              />
            </div>
          )}
        </Card>
      </div>

      <div className="mt-5 grid gap-5 xl:grid-cols-3">
        <Card className="overflow-hidden">
          <PanelTitle title={t('platformAdmin.detail.projects')} meta={projects.data?.totalElements} />
          {projects.isError ? (
            <PlatformErrorState error={projects.error} onRetry={() => projects.refetch()} />
          ) : !projects.data ? (
            <SkeletonTable rows={3} />
          ) : projects.data.content.length === 0 ? (
            <EmptyState icon={FolderOpen} title={t('platformAdmin.detail.noProjects')} className="rounded-none border-0 py-10" />
          ) : (
            <ul className="divide-y divide-rail">
              {projects.data.content.map((project) => (
                <li key={project.id} className="flex items-center justify-between gap-3 px-4 py-3">
                  <span className="min-w-0 truncate text-sm" title={project.name}>{project.name}</span>
                  <span className="flex-shrink-0 font-mono text-xs text-muted-foreground">{formatDate(project.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card className="overflow-hidden xl:col-span-2">
          <PanelTitle title={t('platformAdmin.detail.auditLog')} meta={audit.data?.totalElements} />
          {audit.isError ? (
            <PlatformErrorState error={audit.error} onRetry={() => audit.refetch()} />
          ) : !audit.data ? (
            <SkeletonTable rows={4} />
          ) : audit.data.content.length === 0 ? (
            <EmptyState icon={FileText} title={t('platformAdmin.detail.noAudit')} className="rounded-none border-0 py-10" />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('platformAdmin.columns.time')}</TableHead>
                  <TableHead>{t('platformAdmin.columns.action')}</TableHead>
                  <TableHead>{t('platformAdmin.columns.actor')}</TableHead>
                  <TableHead>{t('platformAdmin.columns.result')}</TableHead>
                  <TableHead>{t('platformAdmin.columns.address')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {audit.data.content.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">
                      {formatDateTimeCompact(entry.createdAt)}
                    </TableCell>
                    <TableCell className="font-mono text-[13px]">
                      {t(`auditLog.actions.${entry.action}`, { defaultValue: entry.action })}
                    </TableCell>
                    <TableCell className="max-w-[14rem] truncate font-mono text-[13px] text-muted-foreground" title={entry.actorEmail ?? undefined}>
                      {entry.actorEmail ?? '—'}
                    </TableCell>
                    <TableCell>
                      <StatusBadge
                        kind={entry.status === 'SUCCESS' ? 'ok' : 'halt'}
                        label={t(entry.status === 'SUCCESS' ? 'auditLog.filters.success' : 'auditLog.filters.failure')}
                      />
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{entry.clientIp ?? '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {audit.data && audit.data.totalElements > auditSize && (
            <div className="px-4 pb-4">
              <TablePagination
                page={auditPage}
                pageSize={auditSize}
                totalElements={audit.data.totalElements}
                totalPages={audit.data.totalPages}
                onPageChange={setAuditPage}
                onPageSizeChange={setAuditSize}
              />
            </div>
          )}
        </Card>
      </div>

      <SuspensionDialog
        organization={org}
        mode={dialog ?? (suspended ? 'reinstate' : 'suspend')}
        open={dialog !== null}
        onOpenChange={(open) => { if (!open) setDialog(null); }}
      />
    </div>
  );
}
