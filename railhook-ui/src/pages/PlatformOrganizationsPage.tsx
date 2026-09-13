import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Building2 } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import { SkeletonTable } from '../components/PageSkeleton';
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Select } from '../components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import { usePlatformOrganizations } from '../api/queries';
import { formatDate } from '../lib/date';
import { FilterBar } from './tableParts';
import {
  EmailText, EventsAgainstLimit, OrganizationLink, OrganizationStatusBadge, PLATFORM_TABLE_HEADER, PlatformErrorState,
  PlatformScope, useDebouncedValue,
} from './platformAdminParts';

/** Every organization on the deployment, searchable by name or by a member's address. */
export default function PlatformOrganizationsPage() {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [searchInput, setSearchInput] = useState('');
  const [suspendedOnly, setSuspendedOnly] = useState(false);
  const search = useDebouncedValue(searchInput.trim());

  const filters = useMemo(() => ({ search: search || undefined, suspendedOnly }), [search, suspendedOnly]);
  const { data, isLoading, isError, error, refetch, isRefetching } = usePlatformOrganizations(page, pageSize, filters);

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={data ? t('platformAdmin.organizations.count', { count: data.totalElements }) : t('platformAdmin.eyebrow')}
        description={t('platformAdmin.organizations.description')}
      />
      <PlatformScope />

      <FilterBar>
        <Input
          type="search"
          value={searchInput}
          onChange={(e) => { setSearchInput(e.target.value); setPage(0); }}
          placeholder={t('platformAdmin.organizations.search')}
          aria-label={t('platformAdmin.organizations.search')}
          className="h-9 w-full sm:w-72"
        />
        <Select
          value={suspendedOnly ? 'suspended' : 'all'}
          onChange={(e) => { setSuspendedOnly(e.target.value === 'suspended'); setPage(0); }}
          aria-label={t('platformAdmin.organizations.statusFilter')}
          className="h-9 w-full sm:w-48"
        >
          <option value="all">{t('platformAdmin.organizations.all')}</option>
          <option value="suspended">{t('platformAdmin.organizations.suspendedOnly')}</option>
        </Select>
      </FilterBar>

      {isError ? (
        <PlatformErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      ) : isLoading || !data ? (
        <Card className="overflow-hidden"><SkeletonTable rows={8} /></Card>
      ) : data.content.length === 0 ? (
        <EmptyState
          icon={Building2}
          title={t('platformAdmin.organizations.empty')}
          description={t('platformAdmin.organizations.emptyDesc')}
        />
      ) : (
        <Card className="overflow-hidden">
          <Table>
            <TableHeader className={PLATFORM_TABLE_HEADER}>
              <TableRow>
                <TableHead>{t('platformAdmin.columns.organization')}</TableHead>
                <TableHead>{t('platformAdmin.columns.plan')}</TableHead>
                <TableHead>{t('platformAdmin.columns.owner')}</TableHead>
                <TableHead className="text-right">{t('platformAdmin.columns.members')}</TableHead>
                <TableHead className="text-right">{t('platformAdmin.columns.projects')}</TableHead>
                <TableHead>{t('platformAdmin.columns.eventsThisMonth')}</TableHead>
                <TableHead>{t('platformAdmin.columns.status')}</TableHead>
                <TableHead className="text-right">{t('platformAdmin.columns.created')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.content.map((organization) => (
                <TableRow key={organization.id}>
                  <TableCell>
                    <OrganizationLink id={organization.id} name={organization.name} className="font-medium" />
                  </TableCell>
                  <TableCell className="font-mono text-[13px]">{organization.planName ?? '—'}</TableCell>
                  <TableCell className="text-muted-foreground">
                    <EmailText email={organization.ownerEmail} />
                  </TableCell>
                  <TableCell className="text-right font-mono text-[13px]">{organization.memberCount}</TableCell>
                  <TableCell className="text-right font-mono text-[13px]">{organization.projectCount}</TableCell>
                  <TableCell>
                    <EventsAgainstLimit current={organization.eventsThisMonth} limit={organization.eventsLimit} />
                  </TableCell>
                  <TableCell><OrganizationStatusBadge organization={organization} /></TableCell>
                  <TableCell className="whitespace-nowrap text-right font-mono text-xs text-muted-foreground">
                    {formatDate(organization.createdAt)}
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
