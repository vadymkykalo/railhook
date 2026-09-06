import { useState, useRef, useEffect, useMemo } from 'react';
import { useNavigate, useLocation, Link } from 'react-router-dom';
import { Check, ChevronsUpDown, Search } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useProjects } from '../api/queries';
import { cn } from '../lib/utils';

interface ProjectSwitcherProps {
  currentProjectId?: string;
  collapsed?: boolean;
}

/** Filtering only earns its keystrokes once the list stops fitting on screen. */
const FILTER_THRESHOLD = 7;

/**
 * The switcher sits under the logo and never leaves the screen, so its whole
 * job is to answer "which project am I in?" before anyone has to ask. The
 * current project gets the name slot and a teal marker; everything else about
 * the control stays quiet.
 */
export default function ProjectSwitcher({ currentProjectId, collapsed }: ProjectSwitcherProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const { data: projects = [] } = useProjects();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const currentProject = projects.find((p) => p.id === currentProjectId);

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? projects.filter((p) => p.name.toLowerCase().includes(q)) : projects;
  }, [projects, query]);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (!open) setQuery('');
  }, [open]);

  if (projects.length === 0) return null;

  const handleSwitch = (newProjectId: string) => {
    setOpen(false);
    if (newProjectId === currentProjectId) return;
    // Land on the same facet of the new project rather than back at its root.
    const subSection = location.pathname.match(/\/admin\/projects\/[^/]+\/(.+)/)?.[1] || 'endpoints';
    navigate(`/admin/projects/${newProjectId}/${subSection}`);
  };

  const initial = (currentProject?.name || '?').charAt(0).toUpperCase();
  const label = currentProject?.name || t('nav.selectProject');

  const menu = open && (
    <div
      className={cn(
        'absolute z-50 mt-1 overflow-hidden rounded-lg border border-rail bg-popover shadow-elevated animate-scale-in',
        collapsed ? 'left-0 top-full w-56' : 'left-0 right-0 top-full'
      )}
    >
      {projects.length >= FILTER_THRESHOLD && (
        <div className="flex items-center gap-2 border-b border-rail px-2.5 py-2">
          <Search className="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" aria-hidden />
          <input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('nav.filterProjects')}
            aria-label={t('nav.filterProjects')}
            className="w-full bg-transparent text-[13px] outline-none placeholder:text-muted-foreground"
          />
        </div>
      )}
      <ul role="listbox" aria-label={t('nav.project')} className="max-h-[280px] overflow-y-auto p-1">
        {matches.length === 0 && (
          <li className="px-2.5 py-3 text-center text-[13px] text-muted-foreground">{t('common.noResults')}</li>
        )}
        {matches.map((project) => {
          const current = project.id === currentProjectId;
          return (
            <li key={project.id} role="option" aria-selected={current}>
              <button
                onClick={() => handleSwitch(project.id)}
                className={cn(
                  'flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left text-[13px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                  current ? 'bg-accent/60 font-medium text-foreground' : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
                )}
              >
                <span
                  aria-hidden
                  className={cn(
                    'flex h-5 w-5 flex-shrink-0 items-center justify-center rounded font-mono text-[10px]',
                    current ? 'bg-primary text-primary-foreground' : 'bg-secondary text-muted-foreground'
                  )}
                >
                  {project.name.charAt(0).toUpperCase()}
                </span>
                <span className="min-w-0 flex-1 truncate">{project.name}</span>
                {current && <Check className="h-3.5 w-3.5 flex-shrink-0 text-primary" aria-hidden />}
              </button>
            </li>
          );
        })}
      </ul>
      <div className="border-t border-rail p-1">
        <Link
          to="/admin/projects"
          onClick={() => setOpen(false)}
          className="block rounded-md px-2 py-1.5 text-[13px] text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
        >
          {t('nav.allProjects')}
        </Link>
      </div>
    </div>
  );

  if (collapsed) {
    return (
      <div ref={ref} className="relative">
        <button
          ref={triggerRef}
          onClick={() => setOpen((o) => !o)}
          aria-haspopup="listbox"
          aria-expanded={open}
          title={label}
          aria-label={t('nav.currentProject', { name: label })}
          className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary font-mono text-[13px] font-medium text-primary-foreground transition-opacity hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        >
          {initial}
        </button>
        {menu}
      </div>
    );
  }

  return (
    <div ref={ref} className="relative">
      <button
        ref={triggerRef}
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={t('nav.currentProject', { name: label })}
        className={cn(
          'flex w-full items-center gap-2.5 rounded-lg border px-2 py-1.5 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
          open ? 'border-primary/50 bg-secondary/60' : 'border-rail hover:bg-secondary/60'
        )}
      >
        <span
          aria-hidden
          className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md bg-primary font-mono text-[13px] font-medium text-primary-foreground"
        >
          {initial}
        </span>
        <span className="min-w-0 flex-1">
          <span className="mono-label block leading-none">{t('nav.project')}</span>
          <span className="mt-0.5 block truncate text-[13px] font-medium leading-tight">{label}</span>
        </span>
        <ChevronsUpDown className="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground" aria-hidden />
      </button>
      {menu}
    </div>
  );
}
