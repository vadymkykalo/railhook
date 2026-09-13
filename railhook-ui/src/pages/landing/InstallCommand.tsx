import { useRef, useState, type KeyboardEvent } from 'react';
import { Check, Copy } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../lib/utils';
import { REPO_URL } from './plans';

export const INSTALL_COMMAND = 'curl -fsSL https://railhook.io/install.sh | bash';

/**
 * The compose tab is what the README's "running it from a clone" section runs. The helm tab
 * points at the chart in the repository, and its requirement line links to the guide, because
 * the chart expects the cluster to already provide the databases.
 */
const METHODS = [
  { id: 'curl', label: 'curl', command: INSTALL_COMMAND, needs: 'docker' },
  { id: 'compose', label: 'docker compose', command: `git clone ${REPO_URL}.git && cd railhook && make up`, needs: 'docker' },
  { id: 'helm', label: 'helm', command: 'helm install railhook ./deploy/helm/railhook', needs: 'kubernetes' },
] as const;

type CopyState = 'idle' | 'copied' | 'failed';

/**
 * The install one-liner on the always-dark code surface, with a copy button.
 *
 * With `tabs`, the reader can switch to the compose or helm route; without, it is only the
 * one-liner (the closing section repeats it and does not need the choice again).
 *
 * The `$` prompt is a pseudo-element, not text: a triple-click selects only the command, and the
 * page's text holds no dollar sign that could be mistaken for a price.
 */
export default function InstallCommand({ tabs = false, id, className }: { tabs?: boolean; id?: string; className?: string }) {
  const { t } = useTranslation();
  const [active, setActive] = useState(0);
  const [copy, setCopy] = useState<CopyState>('idle');
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const method = METHODS[tabs ? active : 0];

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(method.command);
      setCopy('copied');
    } catch {
      setCopy('failed');
    }
    window.setTimeout(() => setCopy('idle'), 1600);
  };

  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>) => {
    const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
    if (!step) return;
    event.preventDefault();
    const next = (active + step + METHODS.length) % METHODS.length;
    setActive(next);
    tabRefs.current[next]?.focus();
  };

  const panelId = id ? `${id}-panel` : undefined;

  return (
    <div id={id} className={cn('mx-auto w-full max-w-[620px] scroll-mt-24 text-left', className)}>
      {tabs && (
        <div className="mb-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-[13px]">
          <p className="font-semibold text-foreground">{t('landing.install.label')}</p>
          {method.needs === 'kubernetes' ? (
            <a href="/docs/self-hosting/kubernetes/" className="text-muted-foreground underline-offset-2 hover:text-foreground hover:underline">
              {t('landing.install.requirementHelm')}
            </a>
          ) : (
            <p className="text-muted-foreground">{t('landing.install.requirementDocker')}</p>
          )}
        </div>
      )}
      <div className="surface-ink overflow-hidden rounded-xl border border-rail">
        {tabs && (
          <div role="tablist" aria-label={t('landing.install.tabsLabel')} className="flex gap-0.5 overflow-x-auto border-b border-rail px-1.5 pt-1.5">
            {METHODS.map((m, i) => (
              <button
                key={m.id}
                ref={(node) => {
                  tabRefs.current[i] = node;
                }}
                type="button"
                role="tab"
                id={`${id}-tab-${m.id}`}
                aria-selected={i === active}
                aria-controls={panelId}
                tabIndex={i === active ? 0 : -1}
                onClick={() => setActive(i)}
                onKeyDown={onTabKey}
                className={cn(
                  'whitespace-nowrap rounded-t-md border-b-2 px-3 py-2 font-mono text-[12.5px] transition-colors',
                  i === active ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground',
                )}
              >
                {m.label}
              </button>
            ))}
          </div>
        )}
        <div
          id={panelId}
          role={tabs ? 'tabpanel' : undefined}
          aria-labelledby={tabs ? `${id}-tab-${method.id}` : undefined}
          className="flex items-center gap-3 py-3 pl-4 pr-3"
        >
          <pre className="min-w-0 flex-1 whitespace-pre-wrap py-1 font-mono text-[13px] leading-relaxed [overflow-wrap:anywhere] before:select-none before:text-muted-foreground before:content-['$_'] sm:overflow-x-auto sm:whitespace-pre sm:text-sm sm:[overflow-wrap:normal]">
            <code>{method.command}</code>
          </pre>
          <button
            type="button"
            onClick={onCopy}
            aria-label={t('landing.install.copyAria')}
            className="inline-flex flex-none items-center gap-1.5 rounded-lg border border-rail px-2.5 py-1.5 text-[12.5px] font-medium text-foreground transition-colors hover:border-muted-foreground"
          >
            {copy === 'copied' ? <Check className="h-3.5 w-3.5 text-ok" aria-hidden="true" /> : <Copy className="h-3.5 w-3.5" aria-hidden="true" />}
            <span aria-live="polite">
              {copy === 'copied' ? t('landing.install.copied') : copy === 'failed' ? t('landing.install.copyFailed') : t('landing.install.copy')}
            </span>
          </button>
        </div>
      </div>
    </div>
  );
}
