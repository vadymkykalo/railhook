import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { collapseUnchanged, lineDiff } from '../lib/lineDiff';

interface DiffViewProps {
  before: string;
  after: string;
  beforeLabel: string;
  afterLabel: string;
  maxHeight?: string;
}

/** Unchanged stretches collapse; "nothing changed" is a valid outcome, not a failure. */
export default function DiffView({
  before, after, beforeLabel, afterLabel, maxHeight = '420px',
}: DiffViewProps) {
  const { t } = useTranslation();
  const diff = useMemo(() => lineDiff(before, after), [before, after]);
  const rows = useMemo(() => collapseUnchanged(diff.lines), [diff.lines]);

  if (diff.added === 0 && diff.removed === 0) {
    return (
      <div className="border border-dashed border-rail p-6 text-center">
        <p className="text-sm font-medium text-foreground">{t('transform.diff.identicalTitle')}</p>
        <p className="mt-1 text-xs text-muted-foreground">{t('transform.diff.identicalHint')}</p>
      </div>
    );
  }

  return (
    <div className="overflow-hidden border border-rail">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-rail bg-muted/40 px-3 py-2 text-[11px]">
        <span className="font-medium text-muted-foreground">
          <span className="text-halt">−</span> {beforeLabel}
        </span>
        <span className="font-medium text-muted-foreground">
          <span className="text-ok">+</span> {afterLabel}
        </span>
        <span className="ml-auto font-mono tabular-nums text-muted-foreground">
          {t('transform.diff.summary', { added: diff.added, removed: diff.removed })}
        </span>
      </div>
      <div className="overflow-auto font-mono text-[11px] leading-[1.6]" style={{ maxHeight }}>
        {rows.map((row, index) => {
          if (row.kind === 'gap') {
            return (
              <div
                key={`gap-${index}`}
                className="flex items-center gap-2 border-y border-rail/60 bg-muted/30 px-3 py-1 text-[10px] text-muted-foreground"
              >
                {t('transform.diff.unchangedLines', { count: row.count })}
              </div>
            );
          }
          const tone = row.kind === 'added'
            ? 'bg-ok/10 text-foreground'
            : row.kind === 'removed'
              ? 'bg-halt/10 text-foreground'
              : 'text-muted-foreground';
          const sign = row.kind === 'added' ? '+' : row.kind === 'removed' ? '−' : ' ';
          return (
            <div key={`${row.kind}-${index}`} className={`flex ${tone}`}>
              <span className="w-10 shrink-0 select-none border-r border-rail/60 px-2 text-right tabular-nums text-muted-foreground">
                {row.leftNumber ?? ''}
              </span>
              <span className="w-10 shrink-0 select-none border-r border-rail/60 px-2 text-right tabular-nums text-muted-foreground">
                {row.rightNumber ?? ''}
              </span>
              <span
                aria-hidden="true"
                className={`w-5 shrink-0 select-none text-center ${
                  row.kind === 'added' ? 'text-ok' : row.kind === 'removed' ? 'text-halt' : 'text-muted-foreground'
                }`}
              >
                {sign}
              </span>
              <span className="min-w-0 flex-1 whitespace-pre-wrap break-all px-2">{row.text || ' '}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
