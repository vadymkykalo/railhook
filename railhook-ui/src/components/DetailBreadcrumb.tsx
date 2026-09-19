import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, ChevronRight } from 'lucide-react';
import { sectionFor, segmentOf, type NavEntry } from '../layout/nav.config';

/**
 * Where a detail page sits: the section, the list it belongs to, then the thing itself.
 *
 * <p>Read from `nav.config` rather than passed in, so a detail page cannot name a parent the
 * rail disagrees with — when the menu moves, the breadcrumb moves with it. Detail pages had
 * none: an event opened from a delivery, or a source opened from a link, left the person with
 * the browser's back button as the only way to the list.
 *
 * <p>On a phone only the list is shown, as a back link: three crumbs do not fit beside a URL.
 */
export default function DetailBreadcrumb({ projectId, current }: { projectId?: string; current: string }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const section = sectionFor(pathname);
  if (!section) return null;

  const segment = segmentOf(pathname);
  const list: NavEntry = section.tabs.find((tab) => tab.owns.includes(segment)) ?? section;
  const crumbs = list === section ? [section] : [section, list];

  return (
    <nav aria-label={t('nav.breadcrumb')} className="mb-3 text-[13px] text-muted-foreground">
      <Link
        to={list.path(projectId)}
        className="inline-flex min-h-11 items-center gap-1.5 hover:text-foreground sm:hidden"
      >
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden />
        {t(list.nameKey)}
      </Link>
      <ol className="hidden flex-wrap items-center gap-1.5 sm:flex">
        {crumbs.map((entry) => (
          <li key={entry.nameKey} className="flex items-center gap-1.5">
            <Link to={entry.path(projectId)} className="underline-offset-4 hover:text-foreground hover:underline">
              {t(entry.nameKey)}
            </Link>
            <ChevronRight className="h-3.5 w-3.5" aria-hidden />
          </li>
        ))}
        <li aria-current="page" className="min-w-0 truncate text-foreground">{current}</li>
      </ol>
    </nav>
  );
}
