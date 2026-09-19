import { useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useProjects } from '../api/queries';
import { SETTINGS_SECTION, sectionFor, segmentOf } from '../layout/nav.config';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import CreateProjectDialog from '../components/CreateProjectDialog';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';

/**
 * Where a rail entry leads when the organization has no project yet.
 *
 * <p>Every section of the dashboard lives inside a project, and a brand-new organization used to
 * find that out by clicking six links that all bounced to the projects list. This screen names the
 * section, says in one sentence what it is for, and offers the one action that unlocks it — then
 * carries on to the section that was clicked, so creating the project is a step on the way rather
 * than a detour.
 */
export default function ProjectSetupPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { segment = '' } = useParams();
  const { data: projects = [], isLoading } = useProjects();
  const { canCreateProject } = usePermissions();
  const [creating, setCreating] = useState(false);

  const section = sectionFor(`/admin/start/${segment}`);

  if (isLoading) {
    return (
      <PageSkeleton>
        <SkeletonCards count={1} height="h-48" />
      </PageSkeleton>
    );
  }

  // Someone created a project in another tab, or followed an old link: there is nothing to set up.
  if (projects.length > 0) return <Navigate to={`/admin/projects/${projects[0].id}/${segment}`} replace />;
  if (!section) return <Navigate to="/admin/dashboard" replace />;

  // Settings is not a thing to set up, but its one project-scoped tab is: name that tab.
  const entry = section === SETTINGS_SECTION
    ? section.tabs.find((tab) => tab.owns.includes(segmentOf(`/admin/start/${segment}`))) ?? section
    : section;
  const Icon = entry.icon;
  const purpose = entry.nameKey.replace(/^nav\./, '');

  return (
    <div className="p-4 lg:p-6">
      <Card className="mx-auto mt-4 max-w-xl p-6 sm:p-8">
        <div className="flex h-11 w-11 items-center justify-center rounded-lg bg-accent text-primary">
          <Icon className="h-5 w-5" aria-hidden />
        </div>
        <h1 className="mt-4 text-title">{t(entry.nameKey)}</h1>
        <p className="mt-2 text-sm text-muted-foreground">{t(`setup.purpose.${purpose}`)}</p>
        <p className="mt-4 text-sm">{canCreateProject ? t('setup.needsProject') : t('setup.needsProjectViewer')}</p>
        <PermissionGate allowed={canCreateProject}>
          <VerificationGate>
            <Button className="mt-5 max-sm:w-full" onClick={() => setCreating(true)}>
              <Plus className="h-4 w-4" aria-hidden />
              {t('setup.createProject')}
            </Button>
          </VerificationGate>
        </PermissionGate>
      </Card>

      <CreateProjectDialog
        open={creating}
        onOpenChange={setCreating}
        onCreated={(project) => navigate(`/admin/projects/${project.id}/${segment}`, { replace: true })}
      />
    </div>
  );
}
