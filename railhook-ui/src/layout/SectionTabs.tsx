import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronRight } from 'lucide-react';
import { cn } from '../lib/utils';
import { hasMinRole, type Role } from '../auth/ProtectedRoute';
import { hintKeyOf, sectionFor, segmentOf } from './nav.config';

/**
 * The second level of navigation, rendered once by the layout rather than by
 * every page, so a page cannot disagree with the rail about where it lives.
 *
 * <p>It looks like a tab strip and it is not one. These are `<Link>`s that
 * change the route; ARIA tabs are required to control a `tabpanel`, and there
 * is none here, so `role="tablist"` had a screen reader announce "tab 1 of 8"
 * and then wait for a panel that never arrives. The rail one level up
 * (`Sidebar.tsx`) already got this right with `aria-current="page"` — two
 * levels of the same menu, in the same directory, disagreeing. This is the
 * navigation markup, matching its sibling.
 *
 * <p>On a phone the strip scrolls sideways, and a strip that is cut off at the screen's edge
 * looks like one that ends there: Connections showed three of its nine tabs with nothing to
 * say there were more. A fade on whichever side still has tabs says so, and the current tab is
 * scrolled into view so a deep link does not land with its own tab off screen.
 */
function useOverflow(key: string | undefined) {
  const ref = useRef<HTMLElement | null>(null);
  const [more, setMore] = useState({ start: false, end: false });

  const measure = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const start = el.scrollLeft > 1;
    const end = el.scrollLeft + el.clientWidth < el.scrollWidth - 1;
    setMore((prev) => (prev.start === start && prev.end === end ? prev : { start, end }));
  }, []);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    measure();
    el.addEventListener('scroll', measure, { passive: true });
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(el);
    return () => {
      el.removeEventListener('scroll', measure);
      observer?.disconnect();
    };
  }, [measure, key]);

  return { ref, more, measure };
}

export default function SectionTabs({ projectId, role }: { projectId?: string; role: Role }) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const section = sectionFor(location.pathname);
  const segment = segmentOf(location.pathname);
  const { ref, more, measure } = useOverflow(section?.nameKey);
  const activeRef = useRef<HTMLAnchorElement | null>(null);

  useLayoutEffect(() => {
    activeRef.current?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
    measure();
  }, [segment, measure]);

  if (!section) return null;

  const tabs = section.tabs.filter((tab) => !tab.requiredRole || hasMinRole(role, tab.requiredRole));
  if (tabs.length < 2) return null;

  return (
    <div
      className="relative border-b border-rail bg-background"
      data-more-start={more.start || undefined}
      data-more-end={more.end || undefined}
    >
      <nav
        ref={ref}
        aria-label={t(section.nameKey)}
        className="flex gap-1 overflow-x-auto px-4 [scrollbar-width:none] lg:px-6 [&::-webkit-scrollbar]:hidden"
      >
        {tabs.map((tab) => {
          const active = tab.owns.includes(segment);
          const Icon = tab.icon;
          const hint = hintKeyOf(tab);
          return (
            <Link
              key={tab.nameKey + tab.owns[0]}
              ref={active ? activeRef : undefined}
              to={tab.path(projectId)}
              title={i18n.exists(hint) ? t(hint) : undefined}
              aria-current={active ? 'page' : undefined}
              className={cn(
                'flex items-center gap-1.5 whitespace-nowrap border-b-2 px-2.5 py-2.5 text-[13px] transition-colors max-sm:min-h-11',
                active
                  ? 'border-primary font-medium text-foreground'
                  : 'border-transparent text-muted-foreground hover:border-rail hover:text-foreground'
              )}
            >
              <Icon className="h-3.5 w-3.5 flex-shrink-0" />
              {t(tab.nameKey)}
            </Link>
          );
        })}
      </nav>
      {more.start && (
        <div aria-hidden className="pointer-events-none absolute inset-y-0 left-0 w-10 bg-gradient-to-r from-background to-transparent" />
      )}
      {more.end && (
        <div aria-hidden className="pointer-events-none absolute inset-y-0 right-0 flex w-12 items-center justify-end bg-gradient-to-l from-background via-background/80 to-transparent pr-1 text-muted-foreground">
          <ChevronRight className="h-4 w-4" />
        </div>
      )}
    </div>
  );
}
