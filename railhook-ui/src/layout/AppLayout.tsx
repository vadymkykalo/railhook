import { ErrorBoundary } from '../components/ErrorBoundary';
import { useState, useEffect } from 'react';
import { Outlet, useNavigate, useLocation, useParams } from 'react-router-dom';
import { Menu, Search, Mail, Loader2, Moon, Sun } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { useAuth } from '../auth/auth.store';
import { authApi } from '../api/auth.api';
import { Button } from '../components/ui/button';
import { cn } from '../lib/utils';
import { usePermissions } from '../auth/usePermissions';
import { showApiError, showSuccess } from '../lib/toast';
import { CommandPalette } from '../components/CommandPalette';
import { getTheme, setTheme } from '../lib/theme';
import LanguageSwitcher from '../components/LanguageSwitcher';
import ProtectedRoute from '../auth/ProtectedRoute';
import Sidebar from './Sidebar';
import SectionTabs from './SectionTabs';
import { requiredRoleFor, sectionFor } from './nav.config';
import { useProjects } from '../api/queries';

const COLLAPSED_KEY = 'sidebar-collapsed';

export default function AppLayout() {
  const { t } = useTranslation();
  const { user, logout, updateUser } = useAuth();
  const { role } = usePermissions();
  const navigate = useNavigate();
  const location = useLocation();
  const params = useParams();

  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSED_KEY) === '1');
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));
  const [resending, setResending] = useState(false);

  const routeProjectId = params.projectId || location.pathname.match(/\/admin\/projects\/([^/]+)/)?.[1];

  /**
   * The rail is project-scoped, and `/admin/projects`, `/admin/dashboard` and
   * the org-level pages carry no project in the URL. `nav.config` used to
   * resolve that to `/admin/projects` — the page you are usually already on —
   * so on the projects list every rail entry was a link that changed nothing.
   * It reads as six broken buttons, and the switcher above them compounded it
   * by saying "Select project" while exactly one existed.
   *
   * So the layout picks one up: the URL's, else the first the account has. With
   * no projects at all there is nothing to fall back to and `/admin/projects`
   * becomes the honest destination again — go make one.
   */
  const { data: projects = [] } = useProjects();
  const projectId = routeProjectId ?? projects[0]?.id;
  const needsVerification = user?.user?.status === 'PENDING_VERIFICATION';
  const section = sectionFor(location.pathname);

  useEffect(() => {
    authApi.getCurrentUser().then((freshUser) => {
      if (freshUser.user?.status !== user?.user?.status) {
        updateUser(freshUser);
      }
    }).catch(() => { });
  }, [location.pathname]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    setSidebarOpen(false);
  }, [location.pathname]);

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      localStorage.setItem(COLLAPSED_KEY, next ? '1' : '0');
      return next;
    });
  };

  const handleResendVerification = async () => {
    if (!user?.user?.email) return;
    setResending(true);
    try {
      await authApi.resendVerification(user.user.email);
      showSuccess(t('auth.verification.sent'));
    } catch (err: any) {
      showApiError(err, 'auth.verification.failed');
    } finally {
      setResending(false);
    }
  };

  const handleLogout = () => {
    logout();
    showSuccess(t('nav.loggedOut'));
    navigate('/login');
  };

  if (!user) return null;

  const openPalette = () =>
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }));

  return (
    <div className="min-h-screen bg-background">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-[100] focus:rounded-md focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-foreground"
      >
        {t('nav.skipToContent')}
      </a>

      <div className="flex h-screen overflow-hidden">
        <aside
          className={cn(
            'hidden border-r border-rail transition-[width] duration-200 lg:flex lg:flex-col',
            collapsed ? 'lg:w-[60px]' : 'lg:w-[var(--sidebar-width)]'
          )}
        >
          <Sidebar
            projectId={projectId}
            role={role}
            user={user}
            collapsed={collapsed}
            onToggleCollapsed={toggleCollapsed}
            onLogout={handleLogout}
          />
        </aside>

        {sidebarOpen && (
          <div className="fixed inset-0 z-50 lg:hidden">
            <div className="fixed inset-0 bg-foreground/30" onClick={() => setSidebarOpen(false)} />
            <aside className="animate-slide-in-left fixed inset-y-0 left-0 w-64 border-r border-rail shadow-elevated">
              <Sidebar
                projectId={projectId}
                role={role}
                user={user}
                collapsed={false}
                onToggleCollapsed={toggleCollapsed}
                isMobile
                onNavigate={() => setSidebarOpen(false)}
                onLogout={handleLogout}
              />
            </aside>
          </div>
        )}

        <div className="flex flex-1 flex-col overflow-hidden">
          <header className="sticky top-0 z-30 flex h-14 flex-shrink-0 items-center gap-3 border-b border-rail bg-background px-4 lg:px-6">
            <Button variant="ghost" size="icon-sm" onClick={() => setSidebarOpen(true)} className="lg:hidden"
              title={t('nav.openMenu')} aria-label={t('nav.openMenu')}>
              <Menu className="h-5 w-5" />
            </Button>

            {section && (
              <h1 className="truncate text-[15px] font-medium">{t(section.nameKey)}</h1>
            )}

            <div className="flex-1" />

            <div className="flex items-center gap-1">
              <Button variant="ghost" size="sm" onClick={openPalette}
                className="hidden gap-2 text-muted-foreground sm:inline-flex">
                <Search className="h-4 w-4" />
                <span className="text-xs">{t('nav.search')}</span>
                <kbd className="ml-1 rounded border border-rail bg-secondary px-1.5 py-0.5 font-mono text-[10px]">⌘K</kbd>
              </Button>
              <Button variant="ghost" size="icon-sm" onClick={openPalette} className="text-muted-foreground sm:hidden"
                title={t('nav.search')} aria-label={t('nav.search')}>
                <Search className="h-4 w-4" />
              </Button>
              <LanguageSwitcher />
              <Button
                variant="ghost"
                size="icon-sm"
                className="text-muted-foreground"
                title={t('nav.toggleTheme')}
                aria-label={t('nav.toggleTheme')}
                onClick={() => {
                  const next = getTheme() === 'dark' ? 'light' : 'dark';
                  setTheme(next);
                  setIsDark(next === 'dark');
                }}
              >
                {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
              </Button>
            </div>
          </header>

          <SectionTabs projectId={projectId} role={role} />

          {needsVerification && (
            <div className="flex items-center justify-between gap-4 border-b border-retry/30 bg-retry-soft px-4 py-2.5 lg:px-6">
              <div className="flex items-center gap-2 text-sm text-retry">
                <Mail className="h-4 w-4 flex-shrink-0" />
                <span>
                  <Trans i18nKey="auth.verification.banner" values={{ email: user?.user?.email }} components={{ strong: <strong /> }} />
                </span>
              </div>
              <Button variant="outline" size="sm" onClick={handleResendVerification} disabled={resending}
                className="flex-shrink-0 border-retry/40 text-retry hover:bg-retry/10">
                {resending && <Loader2 className="mr-1 h-3 w-3 animate-spin" />}
                {resending ? t('auth.verification.resending') : t('auth.verification.resend')}
              </Button>
            </div>
          )}

          {/* The role gate for every /admin page, applied here rather than route
              by route so it reads from the same nav.config table the sidebar and
              the tab strip filter from. Inside <main> on purpose: a refusal is a
              page, and the person keeps their navigation to go somewhere else. */}
          <main id="main-content" tabIndex={-1} className="flex-1 overflow-y-auto">
            <div className="animate-fade-in">
              <ProtectedRoute requiredRole={requiredRoleFor(location.pathname)}>
                {/*
                  Keyed by path so the boundary resets on navigation: a class component holds
                  its error state forever otherwise, and the user who clicks another nav item
                  would keep seeing the page that broke.
                */}
                <ErrorBoundary variant="page" key={location.pathname}>
                  <Outlet />
                </ErrorBoundary>
              </ProtectedRoute>
            </div>
          </main>
        </div>
      </div>
      <CommandPalette />
    </div>
  );
}
