import { useTranslation } from 'react-i18next';
import type { DiffEntry, DiffType } from '../api/eventDiff.api';
import JsonEditor from './JsonEditor';
import { formatJson } from '../lib/json';

/**
 * Two payloads and what moved between them.
 *
 * The marks do the work: `+` a field appeared, `−` it went, `~` it changed.
 * They used to be green/red/amber fills, which is the status palette this
 * product reserves for what a Delivery is doing — a diff is not a status, so
 * the difference is carried by the mark, the mono voice and the rail instead.
 */

interface EventDiffViewProps {
  leftPayload: string;
  rightPayload: string;
  diffs: DiffEntry[];
  leftLabel?: string;
  rightLabel?: string;
}

const MARK: Record<DiffType, string> = {
  ADDED: '+',
  REMOVED: '−',
  CHANGED: '~',
};

function formatValue(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'string') return `"${value}"`;
  return String(value);
}


export default function EventDiffView({ leftPayload, rightPayload, diffs, leftLabel, rightLabel }: EventDiffViewProps) {
  const { t } = useTranslation();

  return (
    <div className="space-y-4">
      {diffs.length > 0 && (
        <div className="space-y-2">
          <p className="mono-label">{t('eventDiff.changes', { count: diffs.length })}</p>
          <ul className="divide-y divide-rail overflow-hidden rounded-lg border border-rail">
            {diffs.map((diff, i) => (
              <li key={`${diff.path}-${i}`} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-3 py-2 text-xs">
                <span className="w-3 flex-shrink-0 font-mono font-medium text-muted-foreground" aria-hidden>
                  {MARK[diff.type]}
                </span>
                <span className="sr-only">{t(`eventDiff.diffType.${diff.type}`)}</span>
                <span className="min-w-0 flex-1 truncate font-mono">{diff.path}</span>
                <span className="flex flex-wrap items-baseline gap-1.5 font-mono text-[11px] text-muted-foreground">
                  {diff.type === 'CHANGED' && (
                    <>
                      <span className="line-through">{formatValue(diff.leftValue)}</span>
                      <span aria-hidden>→</span>
                      <span className="text-foreground">{formatValue(diff.rightValue)}</span>
                    </>
                  )}
                  {diff.type === 'ADDED' && <span className="text-foreground">{formatValue(diff.rightValue)}</span>}
                  {diff.type === 'REMOVED' && <span className="line-through">{formatValue(diff.leftValue)}</span>}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {diffs.length === 0 && (
        <p className="py-4 text-center text-sm text-muted-foreground">{t('eventDiff.noDiffs')}</p>
      )}

      <div className="grid gap-4 xl:grid-cols-2">
        <div className="space-y-1.5">
          <p className="mono-label truncate">{leftLabel || t('eventDiff.left')}</p>
          <JsonEditor value={formatJson(leftPayload)} readOnly minHeight="220px" maxHeight="380px" />
        </div>
        <div className="space-y-1.5">
          <p className="mono-label truncate">{rightLabel || t('eventDiff.right')}</p>
          <JsonEditor value={formatJson(rightPayload)} readOnly minHeight="220px" maxHeight="380px" />
        </div>
      </div>
    </div>
  );
}
