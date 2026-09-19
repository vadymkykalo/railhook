import { useState, type ReactNode } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { BookOpen, ChevronDown, HelpCircle } from 'lucide-react';
import { docsUrl } from '../lib/docsUrl';
import { cn } from '../lib/utils';

/**
 * "What is this screen for, and what do I do first" — two to four numbered steps under a page's
 * header, with the docs page that explains the rest.
 *
 * The steps live in the locale files as `guides.<id>.step1` … `step4`, and the guide shows as many
 * as exist, so a page opts in with one id rather than a list of keys. Open until a reader closes
 * it, and closed from then on for that page only: someone who has learned Endpoints has not
 * necessarily learned Consumers.
 */

export const PAGE_GUIDES_KEY = 'railhook_page_guides';

const MAX_STEPS = 4;

/** One row on a wide screen, however many steps there are. */
const COLUMNS: Record<number, string> = {
  1: '',
  2: 'sm:grid-cols-2',
  3: 'md:grid-cols-3',
  4: 'sm:grid-cols-2 xl:grid-cols-4',
};

/** Collapsed pages when storage refuses, so a click still sticks for this page load. */
const unstored: Record<string, 'collapsed'> = {};

function readCollapsed(): Record<string, 'collapsed'> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(PAGE_GUIDES_KEY) ?? '{}');
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, 'collapsed'>) : {};
  } catch {
    return unstored;
  }
}

function writeCollapsed(id: string, collapsed: boolean): void {
  const next = { ...readCollapsed() };
  if (collapsed) next[id] = 'collapsed';
  else delete next[id];
  if (collapsed) unstored[id] = 'collapsed';
  else delete unstored[id];
  try {
    localStorage.setItem(PAGE_GUIDES_KEY, JSON.stringify(next));
  } catch { /* remembered in memory for this page load instead */ }
}

export interface PageGuideProps {
  /** Names the `guides.<id>` block in the locale files, and the page the open state is kept for. */
  id: string;
  /** A docs page slug, e.g. `outgoing/retries`. Opened in the reader's language. */
  docsLink?: string;
  /** Shown under the steps while open — the code for the step that needs code. */
  children?: ReactNode;
  className?: string;
}

export default function PageGuide({ id, docsLink, children, className }: PageGuideProps) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(() => readCollapsed()[id] !== 'collapsed');

  const steps: string[] = [];
  for (let n = 1; n <= MAX_STEPS; n += 1) {
    const key = `guides.${id}.step${n}`;
    if (i18n.exists(key)) steps.push(key);
  }
  if (steps.length === 0) return null;

  const toggle = () => {
    writeCollapsed(id, open);
    setOpen(!open);
  };

  const panelId = `page-guide-${id}`;

  return (
    <section className={cn('mb-5', !open && '-mt-2', className)}>
      <button
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={t('guides.title')}
        className="inline-flex items-center gap-1.5 rounded-md py-1 text-[13px] font-medium text-muted-foreground transition-colors hover:text-foreground"
      >
        <HelpCircle className="h-3.5 w-3.5" aria-hidden />
        <span aria-hidden>{t('guides.title')}</span>
        <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', open && 'rotate-180')} aria-hidden />
      </button>

      {open && (
        <div id={panelId} className="mt-2 rounded-lg border border-rail bg-card p-4">
          <ol className={cn('grid gap-3', COLUMNS[steps.length])}>
            {steps.map((key, i) => (
              <li key={key} className="flex min-w-0 items-start gap-2.5">
                <span
                  aria-hidden
                  className="mt-px flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full border border-rail font-mono text-[10px] text-muted-foreground"
                >
                  {i + 1}
                </span>
                <span className="min-w-0 text-[13px] leading-snug text-muted-foreground [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:font-mono [&_code]:text-[12px] [&_strong]:font-medium [&_strong]:text-foreground">
                  <Trans i18nKey={key} components={{ code: <code />, strong: <strong /> }} />
                </span>
              </li>
            ))}
          </ol>

          {children && <div className="mt-4 min-w-0">{children}</div>}

          {docsLink && (
            <a
              href={docsUrl(i18n.language, docsLink)}
              className="mt-3 inline-flex items-center gap-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
            >
              <BookOpen className="h-3.5 w-3.5" aria-hidden />
              {t('guides.docs')}
            </a>
          )}
        </div>
      )}
    </section>
  );
}
