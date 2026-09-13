import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Users } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import { SkeletonTable } from '../components/PageSkeleton';
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import { usePlatformUsers } from '../api/queries';
import { formatDate, formatRelativeTime } from '../lib/date';
import { FilterBar } from './tableParts';
import {
  EmailText, OrganizationLink, PLATFORM_TABLE, PLATFORM_TABLE_HEADER, PlatformAdminBadge, PlatformErrorState, PlatformScope, SignInMethods, UserStatusBadge, VerifiedBadge, useDebouncedValue,
} from './platformAdminParts';

/** Every account on the deployment, newest first. */
export default function PlatformUsersPage() {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [searchInput, setSearchInput] = useState('');
  const search = useDebouncedValue(searchInput.trim());
  const { data, isLoading, isError, error, refetch, isRefetching } = usePlatformUsers(page, pageSize, search);

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={data ? t('platformAdmin.users.count', { count: data.totalElements }) : t('platformAdmin.eyebrow')}
        description={t('platformAdmin.users.description')}
      />
      <PlatformScope />

      <FilterBar>
        <Input
          type="search"
          value={searchInput}
          onChange={(e) => { setSearchInput(e.target.value); setPage(0); }}
          placeholder={t('platformAdmin.users.search')}
          aria-label={t('platformAdmin.users.search')}
          className="h-9 w-full sm:w-72"
        />
      </FilterBar>

      {isError ? (
        <PlatformErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      ) : isLoading || !data ? (
        <Card className="overflow-hidden"><SkeletonTable rows={8} /></Card>
      ) : data.content.length === 0 ? (
        <EmptyState icon={Users} title={t('platformAdmin.users.empty')} description={t('platformAdmin.users.emptyDesc')} />
      ) : (
        <Card className="overflow-hidden">
          <Table className={PLATFORM_TABLE}>
            <TableHeader className={PLATFORM_TABLE_HEADER}>
              <TableRow>
                <TableHead>{t('platformAdmin.columns.account')}</TableHead>
                <TableHead>{t('platformAdmin.columns.verified')}</TableHead>
                <TableHead>{t('platformAdmin.columns.status')}</TableHead>
                <TableHead>{t('platformAdmin.columns.signIn')}</TableHead>
                <TableHead>{t('platformAdmin.columns.organizations')}</TableHead>
                <TableHead className="text-right">{t('platformAdmin.columns.lastSeen')}</TableHead>
                <TableHead className="text-right">{t('platformAdmin.columns.created')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.content.map((user) => (
                <TableRow key={user.id}>
                  <TableCell>
                    <EmailText email={user.email} />
                    {user.fullName && <p className="max-w-[13rem] truncate text-xs text-muted-foreground max-sm:max-w-full" title={user.fullName}>{user.fullName}</p>}
                    {user.platformAdmin && <div className="mt-1"><PlatformAdminBadge /></div>}
                  </TableCell>
                  <TableCell><VerifiedBadge verified={user.emailVerified} /></TableCell>
                  <TableCell><UserStatusBadge status={user.status} /></TableCell>
                  <TableCell><SignInMethods methods={user.signInMethods} /></TableCell>
                  <TableCell>
                    {user.organizations.length === 0 ? (
                      <span className="text-muted-foreground">{t('platformAdmin.users.noOrganization')}</span>
                    ) : (
                      <ul className="space-y-0.5">
                        {user.organizations.map((organization) => (
                          <li key={organization.id} className="flex items-baseline gap-1.5 text-[13px] max-sm:justify-end">
                            <OrganizationLink id={organization.id} name={organization.name} className="min-w-0" />
                            <span className="flex-shrink-0 font-mono text-[11px] text-muted-foreground">
                              {t(`members.roles.${organization.role}`, { defaultValue: organization.role })}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-right font-mono text-xs text-muted-foreground">
                    {user.lastSeenAt ? formatRelativeTime(user.lastSeenAt) : '—'}
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-right font-mono text-xs text-muted-foreground">
                    {formatDate(user.createdAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      {!isError && data && data.content.length > 0 && (
        <TablePagination
          page={page}
          pageSize={pageSize}
          totalElements={data.totalElements}
          totalPages={data.totalPages}
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
        />
      )}
    </div>
  );
}
