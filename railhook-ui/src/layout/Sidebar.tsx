import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { BookOpen, ChevronsLeft, LogOut, Settings, ShieldCheck, X } from 'lucide-react';
import { RailhookIcon } from '../components/icons/RailhookIcon';
import { Button } from '../components/ui/button';
import { cn } from '../lib/utils';
import { docsUrl } from '../lib/docsUrl';
import { hasMinRole, type Role } from '../auth/ProtectedRoute';
import ProjectSwitcher from '../components/ProjectSwitcher';
import OrganizationSwitcher from '../components/OrganizationSwitcher';
import { PLATFORM_SECTION, PROJECT_SECTIONS, SETTINGS_SECTION, segmentOf, type NavSection } from './nav.config';
import type { CurrentUserResponse } from '../types/api.types';

interface SidebarProps {
  projectId?: string;
  role: Role;
  user: CurrentUserResponse;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  isMobile?: boolean;
  onNavigate?: () => void;
  onLogout: () => void;
}

function RailLink({
  section, projectId, active, collapsed, onNavigate,
}: {
  section: NavSection;
  projectId?: string;
  active: boolean;
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  const { t } = useTranslation();
  const Icon = section.icon;
  const name = t(section.nameKey);

  return (
    <Link
      to={section.path(projectId)}
      onClick={onNavigate}
      aria-current={active ? 'page' : undefined}
      title={collapsed ? name : undefined}
      className={cn(
        'relative flex items-center gap-3 rounded-md px-2.5 py-2 text-sm transition-colors',
        collapsed && 'justify-center px-2',
        active
          ? 'bg-card font-medium text-foreground shadow-[inset_0_0_0_1px_hsl(var(--rail))]'
          : 'text-muted-foreground hover:bg-card/70 hover:text-foreground'
      )}
    >
      <Icon className={cn('h-4 w-4 flex-shrink-0', active && 'text-primary')} />
      {!collapsed && <span className="truncate">{name}</span>}
    </Link>
  );
}

export default function Sidebar({
  projectId, role, user, collapsed, onToggleCollapsed, isMobile = false, onNavigate, onLogout,
}: SidebarProps) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const segment = segmentOf(location.pathname);
  const narrow = collapsed && !isMobile;

  return (
    <div className="flex h-full flex-col bg-muted">
      <div className={cn('flex h-14 items-center border-b border-rail px-3', narrow && 'justify-center px-2')}>
        <Link to="/" className="flex items-center gap-2 transition-opacity hover:opacity-70">
          <div className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md bg-primary">
            <RailhookIcon className="h-3.5 w-3.5 text-primary-foreground" />
          </div>
          {!narrow && <span className="text-[15px] font-semibold tracking-tight">Railhook</span>}
        </Link>
        {isMobile ? (
          <Button variant="ghost" size="icon-sm" onClick={onNavigate} className="ml-auto"
            title={t('common.close')} aria-label={t('common.close')}>
            <X className="h-4 w-4" />
          </Button>
        ) : (
          <Button variant="ghost" size="icon-sm" onClick={onToggleCollapsed}
            className={cn('ml-auto text-muted-foreground', narrow && 'ml-0')}
            title={t(collapsed ? 'nav.expandSidebar' : 'nav.collapseSidebar')}
            aria-label={t(collapsed ? 'nav.expandSidebar' : 'nav.collapseSidebar')}>
            <ChevronsLeft className={cn('h-4 w-4 transition-transform', collapsed && 'rotate-180')} />
          </Button>
        )}
      </div>

      {!narrow && (
        <div className="border-b border-rail px-3 py-2.5">
          <ProjectSwitcher currentProjectId={projectId} />
        </div>
      )}

      <nav aria-label={t('nav.navigation')} className="flex-1 space-y-0.5 overflow-y-auto p-2">
        {PROJECT_SECTIONS.filter((section) => !section.requiredRole || hasMinRole(role, section.requiredRole)).map((section) => (
          <RailLink
            key={section.nameKey}
            section={section}
            projectId={projectId}
            active={section.owns.includes(segment)}
            collapsed={narrow}
            onNavigate={isMobile ? onNavigate : undefined}
          />
        ))}
      </nav>

      {/* Documentation and settings. Search is not here: the header bar carries
          it, and it carries it at every width — this copy only rendered on an
          expanded sidebar, so a wide screen showed two identical "Search ⌘K"
          controls at once and a collapsed one showed none. */}
      <div className="space-y-0.5 border-t border-rail p-2">
        {/* A page load, not a route: the docs are their own site at /docs/. */}
        <a
          href={docsUrl(i18n.language)}
          onClick={isMobile ? onNavigate : undefined}
          title={narrow ? t('nav.documentation') : undefined}
          className={cn(
            'flex items-center gap-3 rounded-md px-2.5 py-2 text-sm text-muted-foreground transition-colors hover:bg-secondary/60 hover:text-foreground',
            narrow && 'justify-center px-2'
          )}
        >
          <BookOpen className="h-4 w-4 flex-shrink-0" />
          {!narrow && <span>{t('nav.documentation')}</span>}
        </a>
        {/* Shown to everyone, and it lands on the personal profile — the page
            where a member changes their own password. What the section's
            org-level tabs need is stated in nav.config and filtered there. */}
        <Link
          to={SETTINGS_SECTION.path()}
          onClick={isMobile ? onNavigate : undefined}
          aria-current={SETTINGS_SECTION.owns.includes(segment) ? 'page' : undefined}
          title={narrow ? t('nav.settings') : undefined}
          className={cn(
            'relative flex items-center gap-3 rounded-md px-2.5 py-2 text-sm transition-colors',
            narrow && 'justify-center px-2',
            SETTINGS_SECTION.owns.includes(segment)
              ? 'bg-secondary font-medium text-foreground'
              : 'text-muted-foreground hover:bg-secondary/60 hover:text-foreground'
          )}
        >
          <Settings className="h-4 w-4 flex-shrink-0" />
          {!narrow && <span>{t('nav.settings')}</span>}
        </Link>
        {/* Only for the people who run the deployment. Hiding it is a courtesy, not the
            control: the pages refuse without `platformAdmin`, and the API refuses anyone
            not listed in PLATFORM_ADMIN_EMAILS whatever this renders. */}
        {user.platformAdmin && (
          <Link
            to={PLATFORM_SECTION.path()}
            onClick={isMobile ? onNavigate : undefined}
            aria-current={PLATFORM_SECTION.owns.includes(segment) ? 'page' : undefined}
            title={narrow ? t('nav.platformAdmin') : undefined}
            className={cn(
              'relative flex items-center gap-3 rounded-md px-2.5 py-2 text-sm transition-colors',
              narrow && 'justify-center px-2',
              PLATFORM_SECTION.owns.includes(segment)
                ? 'bg-secondary font-medium text-foreground'
                : 'text-muted-foreground hover:bg-secondary/60 hover:text-foreground'
            )}
          >
            <ShieldCheck className="h-4 w-4 flex-shrink-0" />
            {!narrow && <span>{t('nav.platformAdmin')}</span>}
          </Link>
        )}
      </div>

      <div className="border-t border-rail p-2">
        <div className={cn('flex items-center gap-2.5 px-1 py-1', narrow && 'justify-center px-0')}>
          <div className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full bg-accent">
            <span className="font-mono text-[11px] font-medium text-accent-foreground">
              {user.user?.email?.charAt(0).toUpperCase() || 'U'}
            </span>
          </div>
          {!narrow && (
            <>
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13px] leading-tight">{user.user?.email}</p>
                {/* Renders as the plain name it always was until there is a second organization
                    to switch to, so nobody gets a control over a list of one. */}
                <OrganizationSwitcher />
              </div>
              <Button variant="ghost" size="icon-sm" onClick={onLogout}
                className="flex-shrink-0 text-muted-foreground hover:text-halt"
                title={t('nav.logout')} aria-label={t('nav.logout')}>
                <LogOut className="h-3.5 w-3.5" />
              </Button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
