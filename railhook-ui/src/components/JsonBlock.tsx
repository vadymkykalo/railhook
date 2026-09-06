import { useTranslation } from 'react-i18next';
import { Check, ChevronRight, Copy } from 'lucide-react';
import { formatJson } from '../lib/json';
import { useCopyToClipboard } from '../hooks/useCopyToClipboard';
import { cn } from '../lib/utils';

/**
 * A webhook payload, header map or audit blob, shown the same way everywhere.
 *
 * <p>Every screen that shows one had grown its own: a bare `<pre>` with the
 * same `try { JSON.stringify(JSON.parse(x), null, 2) } catch { return x }`
 * inlined as an IIFE — six times in one file — even though `lib/json.ts`
 * exported exactly that as `formatJson`, and `Workbench`'s `OutputBlock`
 * already framed it. None of those copies could be copied to the clipboard
 * except the two on the test console, which had a private wrapper of their own.
 *
 * <p>`collapsible` is what the delivery sheet needs: four of these per attempt,
 * and an attempt list is unreadable if they are all open. Everywhere else the
 * block is the point, so it is open.
 */
export default function JsonBlock({
  label, value, collapsible = false, maxHeight = 'max-h-40', className,
}: {
  label: string;
  /** Raw text. Pretty-printed when it parses, shown as-is when it does not —
   *  a body that never was JSON is still the honest answer. */
  value: string;
  collapsible?: boolean;
  maxHeight?: string;
  className?: string;
}) {
  const { t } = useTranslation();
  const { copied, copy } = useCopyToClipboard();
  const content = formatJson(value);

  const body = (
    <pre className={cn('overflow-auto whitespace-pre-wrap break-words p-2.5 font-mono text-[11px]', maxHeight)}>
      {content}
    </pre>
  );

  const copyButton = (
    <button
      type="button"
      onClick={(e) => { e.preventDefault(); copy(content); }}
      className="text-muted-foreground transition-colors hover:text-foreground"
      aria-label={t('common.copyNamed', { label })}
      title={t('common.copy')}
    >
      {copied ? <Check className="h-3.5 w-3.5 text-ok" /> : <Copy className="h-3.5 w-3.5" />}
    </button>
  );

  if (!collapsible) {
    return (
      <div className={cn('overflow-hidden rounded-lg border border-rail', className)}>
        <div className="flex items-center justify-between gap-2 border-b border-rail bg-muted/40 px-2.5 py-1.5">
          <span className="mono-label">{label}</span>
          {copyButton}
        </div>
        {body}
      </div>
    );
  }

  return (
    <details className={cn('group overflow-hidden rounded-lg border border-rail', className)}>
      <summary className="flex cursor-pointer items-center justify-between gap-2 border-b border-transparent bg-muted/40 px-2.5 py-1.5 group-open:border-rail">
        <span className="flex items-center gap-1.5">
          <ChevronRight className="h-3 w-3 text-muted-foreground transition-transform group-open:rotate-90" aria-hidden />
          <span className="mono-label">{label}</span>
        </span>
        {copyButton}
      </summary>
      {body}
    </details>
  );
}
