import { useTranslation } from 'react-i18next';
import { Check, ChevronRight, Copy } from 'lucide-react';
import { formatJson } from '../lib/json';
import { useCopyToClipboard } from '../hooks/useCopyToClipboard';
import { cn } from '../lib/utils';

/** collapsible for the delivery sheet, where four per attempt would be unreadable open. */
export default function JsonBlock({
  label, value, collapsible = false, maxHeight = 'max-h-40', className,
}: {
  label: string;
  /** Shown as-is when it doesn't parse: a body that never was JSON is still the honest answer. */
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
      <div className={cn('overflow-hidden border border-rail', className)}>
        <div className="flex items-center justify-between gap-2 border-b border-rail bg-muted/40 px-2.5 py-1.5">
          <span className="mono-label">{label}</span>
          {copyButton}
        </div>
        {body}
      </div>
    );
  }

  return (
    <details className={cn('group overflow-hidden border border-rail', className)}>
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
