import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, ArrowRight, Copy, Download, Loader2 } from 'lucide-react';
import { useAuth } from '../auth/auth.store';
import { usePermissions } from '../auth/usePermissions';
import { organizationsApi } from '../api/organizations.api';
import { useMembers, useProjects } from '../api/queries';
import { showApiError, showSuccess } from '../lib/toast';
import { formatDate } from '../lib/date';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import { ErrorState } from '../components/EmptyState';
import PermissionGate, { ROLES, RoleCard } from '../components/PermissionGate';
import DangerConfirmDialog from '../components/DangerConfirmDialog';
import ConfigExportImport from '../components/ConfigExportImport';
import { FormSection, SaveControl } from './SettingsPage';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';

/**
 * The organization form. Everything a customer owns hangs off exactly one
 * Organization, so this page is where its name, its people and its ending live.
 */
export default function OrgSettingsPage() {
  const { t } = useTranslation();
  const { user, updateUser } = useAuth();
  const { canManageOrgSettings } = usePermissions();

  const orgId = user?.organization?.id || '';
  const orgName = user?.organization?.name || '';
  const orgCreatedAt = user?.organization?.createdAt || '';

  const [name, setName] = useState(orgName);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const dirty = name !== orgName;

  const {
    data: members = [], isLoading: membersLoading, isError: membersFailed,
    error: membersError, refetch: refetchMembers,
  } = useMembers(orgId || undefined);
  const {
    data: projects = [], isLoading: projectsLoading, isError: projectsFailed,
    error: projectsError, refetch: refetchProjects,
  } = useProjects();
  const [exportProjectId, setExportProjectId] = useState('');
  const selectedProject = projects.find((p) => p.id === exportProjectId);

  useEffect(() => {
    if (projects.length > 0 && !exportProjectId) setExportProjectId(projects[0].id);
  }, [projects, exportProjectId]);

  useEffect(() => {
    setName(orgName);
  }, [orgName]);

  const handleSave = async () => {
    if (!orgId || !dirty) return;
    setSaving(true);
    try {
      const updated = await organizationsApi.update(orgId, { name: name.trim() });
      if (user) {
        updateUser({ ...user, organization: { ...user.organization, name: updated.name } });
      }
      setSaved(true);
      showSuccess(t('orgSettings.toast.updated'));
    } catch (err: any) {
      showApiError(err, 'orgSettings.toast.updateFailed');
    } finally {
      setSaving(false);
    }
  };

  const handleCopyId = () => {
    navigator.clipboard.writeText(orgId);
    showSuccess(t('orgSettings.idCopied'));
  };

  if (membersLoading || projectsLoading) {
    return (
      <PageSkeleton maxWidth="max-w-4xl">
        <SkeletonCards count={3} height="h-44" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  // The danger zone spells out what deleting costs from these two counts. A
  // failed fetch would render that as "0 projects, 0 members" — an invitation.
  if (membersFailed || projectsFailed) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState
          error={membersError ?? projectsError}
          onRetry={() => { refetchMembers(); refetchProjects(); }}
        />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <div className="max-w-4xl">
        <PageHeader
          eyebrow={orgCreatedAt ? t('orgSettings.since', { date: formatDate(orgCreatedAt) }) : undefined}
          title={t('orgSettings.title')}
          description={t('orgSettings.subtitle')}
        />

        <div className="space-y-8">
          <FormSection
            title={t('orgSettings.details')}
            description={t('orgSettings.detailsDesc')}
            footer={
              <PermissionGate allowed={canManageOrgSettings} requiredRole="OWNER">
                <SaveControl
                  label={t('common.save')}
                  savingLabel={t('common.saving')}
                  saving={saving}
                  disabled={!dirty || !name.trim()}
                  saved={saved && !dirty}
                  onClick={handleSave}
                />
              </PermissionGate>
            }
          >
            <div className="space-y-2">
              <Label htmlFor="orgName">{t('orgSettings.orgName')}</Label>
              <Input
                id="orgName"
                value={name}
                onChange={(e) => { setName(e.target.value); setSaved(false); }}
                placeholder={t('orgSettings.orgNamePlaceholder')}
                disabled={saving || !canManageOrgSettings}
                className="max-w-sm"
              />
              <p className="text-xs text-muted-foreground">{t('orgSettings.orgNameHint')}</p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="orgId">{t('orgSettings.orgId')}</Label>
              <div className="flex max-w-sm items-center gap-2">
                <Input id="orgId" value={orgId} readOnly className="bg-muted font-mono text-xs" />
                <Button
                  variant="outline"
                  size="icon"
                  onClick={handleCopyId}
                  title={t('common.copyId')}
                  aria-label={t('common.copyId')}
                >
                  <Copy className="h-4 w-4" />
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">{t('orgSettings.orgIdHint')}</p>
            </div>
          </FormSection>

          <FormSection
            title={t('orgSettings.access')}
            description={t('orgSettings.accessDesc')}
          >
            <div className="grid gap-3 sm:grid-cols-3">
              {ROLES.map((role) => (
                <RoleCard key={role} role={role} count={members.filter((m) => m.role === role).length} />
              ))}
            </div>
            <Link
              to="/admin/members"
              className="inline-flex items-center gap-1.5 text-sm text-primary hover:underline"
            >
              {t('orgSettings.manageMembers', { count: members.length })}
              <ArrowRight className="h-3.5 w-3.5" aria-hidden />
            </Link>
          </FormSection>

          {projects.length > 0 && (
            <FormSection
              title={t('orgSettings.configuration')}
              description={t('orgSettings.configurationDesc')}
            >
              {projects.length > 1 && (
                <div className="space-y-2">
                  <Label htmlFor="exportProject">{t('configExport.selectProject')}</Label>
                  <Select
                    id="exportProject"
                    value={exportProjectId}
                    onChange={(e) => setExportProjectId(e.target.value)}
                    className="max-w-sm"
                  >
                    {projects.map((p) => (
                      <option key={p.id} value={p.id}>{p.name}</option>
                    ))}
                  </Select>
                </div>
              )}
              {exportProjectId && selectedProject && (
                <ConfigExportImport projectId={exportProjectId} projectName={selectedProject.name} />
              )}
            </FormSection>
          )}

          {canManageOrgSettings && <GdprExportSection orgId={orgId} />}

          {canManageOrgSettings && <DangerZone orgId={orgId} orgName={orgName} projectCount={projects.length} memberCount={members.length} />}
        </div>
      </div>
    </div>
  );
}

function GdprExportSection({ orgId }: { orgId: string }) {
  const { t } = useTranslation();
  const [exporting, setExporting] = useState(false);

  const handleExport = async () => {
    setExporting(true);
    try {
      const blob = await organizationsApi.exportData(orgId);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `org-${orgId}-export.json`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      a.remove();
      showSuccess(t('orgSettings.exportSuccess'));
    } catch (err: any) {
      showApiError(err, 'orgSettings.exportFailed');
    } finally {
      setExporting(false);
    }
  };

  return (
    <FormSection title={t('orgSettings.gdprExport')} description={t('orgSettings.gdprExportDesc')}>
      <p className="text-sm text-muted-foreground">{t('orgSettings.gdprExportInfo')}</p>
      <Button variant="outline" size="sm" onClick={handleExport} disabled={exporting}>
        {exporting ? <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden /> : <Download className="h-3.5 w-3.5" aria-hidden />}
        {exporting ? t('orgSettings.exporting') : t('orgSettings.exportButton')}
      </Button>
    </FormSection>
  );
}

/**
 * The one action on this page that cannot be undone, kept away from the fields
 * that can. Confirmation runs through DangerConfirmDialog like every other
 * destructive action in the product, so the ritual is always the same one.
 */
function DangerZone({
  orgId, orgName, projectCount, memberCount,
}: {
  orgId: string;
  orgName: string;
  projectCount: number;
  memberCount: number;
}) {
  const { t } = useTranslation();
  const { logout } = useAuth();
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await organizationsApi.delete(orgId);
      showSuccess(t('orgSettings.deleteOrgSuccess'));
      logout();
    } catch (err: any) {
      showApiError(err, 'orgSettings.deleteOrgFailed');
    } finally {
      setDeleting(false);
    }
  };

  return (
    <section className="rounded-xl border border-halt/30 bg-halt-soft/50 p-5">
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-4 w-4 text-halt" aria-hidden />
        <h3 className="text-[15px] font-medium text-halt">{t('orgSettings.dangerZone')}</h3>
      </div>
      <div className="mt-4 flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 max-w-lg">
          <p className="text-sm font-medium">{t('orgSettings.deleteOrg')}</p>
          <p className="mt-1 text-sm text-muted-foreground">{t('orgSettings.deleteOrgDesc')}</p>
        </div>
        <Button variant="destructive" size="sm" onClick={() => setConfirming(true)}>
          {t('orgSettings.deleteOrg')}
        </Button>
      </div>

      <DangerConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t('orgSettings.deleteOrg')}
        description={t('orgSettings.deleteOrgDesc')}
        confirmName={orgName}
        impact={[
          t('orgSettings.deleteImpactProjects', { count: projectCount }),
          t('orgSettings.deleteImpactMembers', { count: memberCount }),
          t('orgSettings.deleteImpactKeys'),
          t('orgSettings.deleteImpactHistory'),
        ]}
        onConfirm={handleDelete}
        loading={deleting}
        confirmLabel={t('orgSettings.deleteOrgButton')}
      />
    </section>
  );
}
