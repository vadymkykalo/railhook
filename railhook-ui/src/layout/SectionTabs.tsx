import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';
import { hasMinRole, type Role } from '../auth/ProtectedRoute';
import { sectionFor, segmentOf } from './nav.config';

/** Links, not ARIA tabs: role="tablist" with no tabpanel left screen readers waiting. */
export default function SectionTabs({ projectId, role }: { projectId?: string; role: Role }) {
  const { t } = useTranslation();
  const location = useLocation();
  const section = sectionFor(location.pathname);
  const segment = segmentOf(location.pathname);

  if (!section) return null;

  const tabs = section.tabs.filter((tab) => !tab.requiredRole || hasMinRole(role, tab.requiredRole));
  if (tabs.length < 2) return null;

  return (
    <div className="border-b border-rail bg-background">
      <nav
        aria-label={t(section.nameKey)}
        className="flex gap-1 overflow-x-auto px-4 lg:px-6"
      >
        {tabs.map((tab) => {
          const active = tab.owns.includes(segment);
          const Icon = tab.icon;
          return (
            <Link
              key={tab.nameKey + tab.owns[0]}
              to={tab.path(projectId)}
              aria-current={active ? 'page' : undefined}
              className={cn(
                'flex items-center gap-1.5 whitespace-nowrap border-b-2 px-2.5 py-2.5 text-[13px] transition-colors max-sm:min-h-11',
                active
                  ? 'border-foreground font-medium text-foreground'
                  : 'border-transparent text-muted-foreground hover:border-rail hover:text-foreground'
              )}
            >
              <Icon className="h-3.5 w-3.5 flex-shrink-0" />
              {t(tab.nameKey)}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
