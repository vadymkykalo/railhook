import { useId, useRef, useSyncExternalStore, type KeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { BookOpen, Check, Copy } from 'lucide-react';
import { highlight, type CodeLanguage } from './SyntaxHighlight';
import { useCopyToClipboard } from '../hooks/useCopyToClipboard';
import { docsUrl } from '../lib/docsUrl';
import { cn } from '../lib/utils';
import type { SnippetLanguage, SnippetSamples } from '../lib/integrationSnippets';

/**
 * The code for the thing this screen configures, in the reader's language, ready to paste.
 *
 * The samples themselves are built in `lib/integrationSnippets.ts`; this only frames them. The
 * language is one choice for the whole dashboard rather than per snippet: someone who writes
 * Python picks it once, and every snippet after that — on this page and the next — opens on it.
 * It is remembered in localStorage when the browser allows it and in memory when it does not,
 * because a private window refusing storage is no reason to lose the tab you just clicked.
 */

export const SNIPPET_LANGUAGE_KEY = 'railhook_snippet_language';

/** The order tabs appear in, whichever subset a snippet offers. */
const ORDER: SnippetLanguage[] = ['curl', 'node', 'python', 'php', 'cli', 'html'];

const LABELS: Record<SnippetLanguage, string> = {
  curl: 'cURL',
  node: 'Node.js',
  python: 'Python',
  php: 'PHP',
  cli: 'Shell',
  html: 'HTML',
};

const GRAMMAR: Record<SnippetLanguage, CodeLanguage> = {
  curl: 'bash',
  node: 'javascript',
  python: 'python',
  php: 'php',
  cli: 'bash',
  html: 'text',
};

const listeners = new Set<() => void>();
let unstoredChoice: SnippetLanguage | null = null;

function isLanguage(value: unknown): value is SnippetLanguage {
  return typeof value === 'string' && (ORDER as string[]).includes(value);
}

function readChoice(): SnippetLanguage | null {
  try {
    const stored = localStorage.getItem(SNIPPET_LANGUAGE_KEY);
    return isLanguage(stored) ? stored : null;
  } catch {
    return unstoredChoice;
  }
}

function writeChoice(language: SnippetLanguage): void {
  unstoredChoice = language;
  try {
    localStorage.setItem(SNIPPET_LANGUAGE_KEY, language);
  } catch { /* kept in memory for this page load instead */ }
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export default function IntegrationSnippet({
  title, samples, docsLink, footer, className,
}: {
  /** What the code does, in a few words: "Send an event", "Verify the signature". */
  title: string;
  samples: SnippetSamples;
  /** A docs page slug, e.g. `outgoing/customer-portal`. Opened in the reader's language. */
  docsLink?: string;
  /** A line under the code: what to substitute, or what happens next. */
  footer?: ReactNode;
  className?: string;
}) {
  const { t, i18n } = useTranslation();
  const baseId = useId();
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const { copied, copy } = useCopyToClipboard();
  const preferred = useSyncExternalStore(subscribe, readChoice, readChoice);

  const available = ORDER.filter((language) => samples[language]);
  if (available.length === 0) return null;

  const active = preferred && available.includes(preferred) ? preferred : available[0];
  const activeIndex = available.indexOf(active);
  const code = samples[active] ?? '';

  const select = (index: number) => {
    const next = (index + available.length) % available.length;
    writeChoice(available[next]);
    tabRefs.current[next]?.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const moves: Record<string, number> = {
      ArrowRight: activeIndex + 1,
      ArrowLeft: activeIndex - 1,
      Home: 0,
      End: available.length - 1,
    };
    if (event.key in moves) {
      event.preventDefault();
      select(moves[event.key]);
    }
  };

  const tabId = (language: SnippetLanguage) => `${baseId}-tab-${language}`;
  const panelId = `${baseId}-panel`;

  return (
    <div className={cn('min-w-0 max-w-full overflow-hidden rounded-lg border border-rail bg-card', className)}>
      <div className="flex items-center justify-between gap-2 border-b border-rail bg-muted/40 px-3 py-1.5">
        <span className="min-w-0 text-[13px] font-medium leading-snug">{title}</span>
        <button
          type="button"
          onClick={() => copy(code)}
          className="flex flex-shrink-0 items-center gap-1 rounded px-1.5 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
          aria-label={t('common.copyNamed', { label: title })}
          title={t('common.copy')}
        >
          {copied ? <Check className="h-3.5 w-3.5 text-ok" aria-hidden /> : <Copy className="h-3.5 w-3.5" aria-hidden />}
          <span className="max-sm:sr-only">{copied ? t('snippet.copied') : t('common.copy')}</span>
        </button>
      </div>

      <div role="tablist" aria-label={t('snippet.languages')} className="flex gap-1 overflow-x-auto border-b border-rail px-2 pt-1.5">
        {available.map((language, i) => (
          <button
            key={language}
            ref={(node) => { tabRefs.current[i] = node; }}
            id={tabId(language)}
            type="button"
            role="tab"
            aria-selected={language === active}
            aria-controls={panelId}
            tabIndex={language === active ? 0 : -1}
            onClick={() => select(i)}
            onKeyDown={onKeyDown}
            className={cn(
              '-mb-px flex-none whitespace-nowrap border-b-2 px-2.5 pb-1.5 pt-1 font-mono text-[12px] transition-colors max-sm:min-h-9',
              language === active
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground',
            )}
          >
            {LABELS[language]}
          </button>
        ))}
      </div>

      <div id={panelId} role="tabpanel" aria-labelledby={tabId(active)} tabIndex={0} className="overflow-x-auto">
        {/* No ligatures: `=>` and `->` must read as what the reader types. */}
        <pre className="p-3 font-mono text-[12px] leading-relaxed [font-variant-ligatures:none]">
          <code className="block w-max min-w-full">{highlight(code, GRAMMAR[active])}</code>
        </pre>
      </div>

      {(footer || docsLink) && (
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 border-t border-rail px-3 py-2 text-xs text-muted-foreground">
          {footer && <span className="min-w-0">{footer}</span>}
          {docsLink && (
            <a
              href={docsUrl(i18n.language, docsLink)}
              className="inline-flex items-center gap-1.5 transition-colors hover:text-foreground"
            >
              <BookOpen className="h-3.5 w-3.5" aria-hidden />
              {t('snippet.docs')}
            </a>
          )}
        </div>
      )}
    </div>
  );
}
