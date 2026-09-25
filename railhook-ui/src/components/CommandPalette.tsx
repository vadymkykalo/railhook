import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { BookOpen, FolderKanban, Search } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { docsUrl } from '../lib/docsUrl';
import { Dialog, DialogContent, DialogTitle } from './ui/dialog';
import { projectsApi } from '../api/projects.api';
import { usePermissions } from '../auth/usePermissions';
import { hasMinRole } from '../auth/ProtectedRoute';
import {
  PROJECT_SECTIONS, SETTINGS_SECTION,
  type NavEntry,
} from '../layout/nav.config';
import type { ProjectResponse } from '../types/api.types';
import { cn } from '../lib/utils';

/** Derived from nav.config.ts so the palette can't drift from the rail. */

interface PaletteItem {
  id: string;
  label: string;
  section: string;
  hint?: string;
  icon: React.ElementType;
  path: string;
  external?: boolean;
}

export function CommandPalette() {
  const { t, i18n } = useTranslation();
  const { role } = usePermissions();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [projects, setProjects] = useState<ProjectResponse[]>([]);
  const navigate = useNavigate();
  const location = useLocation();
  const listRef = useRef<HTMLDivElement>(null);

  const projectId = location.pathname.match(/\/admin\/projects\/([^/]+)/)?.[1];

  const go = useCallback((item: Pick<PaletteItem, 'path' | 'external'>) => {
    if (item.external) window.location.assign(item.path);
    else navigate(item.path);
    setOpen(false);
  }, [navigate]);

  useEffect(() => {
    if (open && projects.length === 0) {
      projectsApi.list().then(setProjects).catch(() => {});
    }
  }, [open, projects.length]);

  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setOpen(prev => !prev);
      }
    };
    document.addEventListener('keydown', down);
    return () => document.removeEventListener('keydown', down);
  }, []);

  const items = useMemo<PaletteItem[]>(() => {
    const allowed = (entry: NavEntry) => !entry.requiredRole || hasMinRole(role, entry.requiredRole);

    const entryItem = (entry: NavEntry, section: string, keyPrefix: string): PaletteItem => ({
      id: `${keyPrefix}:${entry.nameKey}:${entry.owns[0]}`,
      label: t(entry.nameKey),
      section,
      hint: entry.owns[0],
      icon: entry.icon,
      path: entry.path(projectId),
    });

    // A section with tabs is a group; only a tabless section is itself a destination.
    const fromSection = (section: typeof SETTINGS_SECTION, keyPrefix: string): PaletteItem[] => {
      const name = t(section.nameKey);
      const tabs = section.tabs.filter(allowed);
      if (tabs.length === 0) {
        return allowed(section) ? [entryItem(section, name, keyPrefix)] : [];
      }
      return tabs.map((tab) => entryItem(tab, name, keyPrefix));
    };

    const destinations = PROJECT_SECTIONS.flatMap((s, i) => fromSection(s, `s${i}`));

    const settings = fromSection(SETTINGS_SECTION, 'set');

    const projectItems: PaletteItem[] = projects.map((p) => ({
      id: `project:${p.id}`,
      label: p.name,
      section: t('commandPalette.groupProjects'),
      icon: FolderKanban,
      path: `/admin/projects/${p.id}/connections`,
    }));

    const resources: PaletteItem[] = [{
      id: 'docs',
      label: t('commandPalette.documentation'),
      section: t('commandPalette.groupResources'),
      hint: 'docs',
      icon: BookOpen,
      path: docsUrl(i18n.language),
      external: true,
    }];

    return [...destinations, ...settings, ...projectItems, ...resources];
  }, [projects, projectId, role, t, i18n.language]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return items;
    return items.filter((item) =>
      item.label.toLowerCase().includes(q) ||
      item.section.toLowerCase().includes(q) ||
      item.hint?.toLowerCase().includes(q)
    );
  }, [items, query]);

  /** Numbered flat so one index walks the whole list. */
  const groups = useMemo(() => {
    const map = new Map<string, { item: PaletteItem; index: number }[]>();
    filtered.forEach((item, index) => {
      const rows = map.get(item.section) ?? [];
      rows.push({ item, index });
      map.set(item.section, rows);
    });
    return Array.from(map.entries());
  }, [filtered]);

  useEffect(() => {
    setSelectedIndex(0);
  }, [query]);

  useEffect(() => {
    listRef.current
      ?.querySelector(`[data-index="${selectedIndex}"]`)
      ?.scrollIntoView({ block: 'nearest' });
  }, [selectedIndex, filtered.length]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (filtered.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSelectedIndex((i) => (i + 1) % filtered.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelectedIndex((i) => (i - 1 + filtered.length) % filtered.length);
    } else if (e.key === 'Home') {
      e.preventDefault();
      setSelectedIndex(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      setSelectedIndex(filtered.length - 1);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const target = filtered[selectedIndex];
      if (target) go(target);
    }
  };

  const handleOpenChange = (value: boolean) => {
    setOpen(value);
    if (!value) {
      setQuery('');
      setSelectedIndex(0);
    }
  };

  const activeId = filtered[selectedIndex] ? `palette-item-${selectedIndex}` : undefined;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg gap-0 overflow-hidden border-rail p-0 [&>button]:hidden">
        <DialogTitle className="sr-only">{t('commandPalette.title')}</DialogTitle>

        <div className="flex items-center gap-2.5 border-b border-rail px-4">
          <Search className="h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={t('commandPalette.placeholder')}
            aria-label={t('commandPalette.title')}
            role="combobox"
            aria-expanded
            aria-controls="palette-results"
            aria-activedescendant={activeId}
            autoComplete="off"
            className="h-12 flex-1 border-0 bg-transparent px-0 text-sm focus:shadow-none focus:outline-none"
            autoFocus
          />
          <kbd className="hidden flex-shrink-0 rounded border border-rail bg-secondary px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground sm:inline-flex">
            ESC
          </kbd>
        </div>

        <div ref={listRef} className="max-h-[340px] overflow-y-auto p-2">
          {filtered.length === 0 ? (
            <div className="px-3 py-10 text-center">
              <p className="text-sm text-muted-foreground">{t('commandPalette.noResults')}</p>
              <p className="mt-1 text-xs text-muted-foreground">{t('commandPalette.noResultsHint')}</p>
            </div>
          ) : (
            <div id="palette-results" role="listbox" aria-label={t('commandPalette.results')}>
              {groups.map(([section, rows]) => (
                <div key={section} className="mb-1 last:mb-0">
                  <div className="mono-label px-3 py-1.5">{section}</div>
                  {rows.map(({ item, index }) => {
                    const Icon = item.icon;
                    const active = selectedIndex === index;
                    return (
                      <button
                        key={item.id}
                        id={`palette-item-${index}`}
                        data-index={index}
                        role="option"
                        aria-selected={active}
                        type="button"
                        onClick={() => go(item)}
                        onMouseMove={() => setSelectedIndex(index)}
                        className={cn(
                          'flex w-full items-center gap-3 px-3 py-2 text-left text-sm transition-colors',
                          active ? 'bg-secondary text-foreground' : 'text-muted-foreground'
                        )}
                      >
                        <Icon className={cn('h-4 w-4 flex-shrink-0', active && 'text-primary')} aria-hidden />
                        <span className="truncate text-foreground">{item.label}</span>
                        <span className="ml-auto hidden flex-shrink-0 truncate font-mono text-[11px] text-muted-foreground sm:block">
                          {item.hint ? `/${item.hint}` : item.section}
                        </span>
                      </button>
                    );
                  })}
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="flex items-center gap-3 border-t border-rail px-4 py-2 text-[11px] text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <kbd className="rounded border border-rail bg-secondary px-1 py-0.5 font-mono">↑↓</kbd>
            {t('commandPalette.navigate')}
          </span>
          <span className="flex items-center gap-1.5">
            <kbd className="rounded border border-rail bg-secondary px-1 py-0.5 font-mono">↵</kbd>
            {t('commandPalette.open')}
          </span>
          <span className="flex items-center gap-1.5">
            <kbd className="rounded border border-rail bg-secondary px-1 py-0.5 font-mono">esc</kbd>
            {t('commandPalette.close')}
          </span>
        </div>
      </DialogContent>
    </Dialog>
  );
}
