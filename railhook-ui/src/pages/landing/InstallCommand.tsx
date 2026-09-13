import { useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../lib/utils';

export const INSTALL_COMMAND = 'curl -fsSL https://railhook.io/install.sh | bash';

type CopyState = 'idle' | 'copied' | 'failed';

/**
 * The install one-liner on the always-dark code surface, with a copy button.
 *
 * One command for every server: a domain, a proxy in front, a port are installer flags the docs
 * explain, not a choice of methods to make before the reader has even started.
 *
 * With `label`, the hero's heading line (what it is, what it needs) sits above it. The page prints
 * it once, in the hero.
 *
 * The `$` prompt is a pseudo-element, not text: a triple-click selects only the command, and the
 * page's text holds no dollar sign that could be mistaken for a price.
 */
export default function InstallCommand({ label = false, id, className }: { label?: boolean; id?: string; className?: string }) {
  const { t } = useTranslation();
  const [copy, setCopy] = useState<CopyState>('idle');

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(INSTALL_COMMAND);
      setCopy('copied');
    } catch {
      setCopy('failed');
    }
    window.setTimeout(() => setCopy('idle'), 1600);
  };

  return (
    <div id={id} className={cn('mx-auto w-full max-w-[620px] scroll-mt-24 text-left', className)}>
      {label && (
        <div className="mb-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-[13px]">
          <p className="font-semibold text-foreground">{t('landing.install.label')}</p>
          <p className="text-muted-foreground">{t('landing.install.requirementDocker')}</p>
        </div>
      )}
      <div className="surface-ink overflow-hidden rounded-xl border border-rail">
        <div className="flex items-center gap-3 py-3 pl-4 pr-3">
          <pre className="min-w-0 flex-1 whitespace-pre-wrap py-1 font-mono text-[13px] leading-relaxed [overflow-wrap:anywhere] before:select-none before:text-muted-foreground before:content-['$_'] sm:overflow-x-auto sm:whitespace-pre sm:text-sm sm:[overflow-wrap:normal]">
            <code>{INSTALL_COMMAND}</code>
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
