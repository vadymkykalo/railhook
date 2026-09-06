import { memo } from 'react';
import { type NodeProps, Handle, Position } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../../lib/utils';
import { NODE_ROLE_COLOR } from './nodeTypes';

/**
 * The one node that cannot use BaseNode: it has two outputs, and which one a
 * run takes is the whole point of it. So the two handles are labelled rather
 * than coloured — the true branch carries the brand accent as the main path,
 * the false branch is drawn in the same muted role colour as the node itself.
 */
function BranchNode({ data, selected }: NodeProps) {
  const { t } = useTranslation();
  const d = data as Record<string, unknown>;
  const hasConditions = d.conditions != null;
  const accent = NODE_ROLE_COLOR.logic;

  return (
    <div
      className={cn(
        'relative min-w-[180px] max-w-[240px] overflow-hidden rounded-lg border bg-card shadow-card transition-colors',
        selected ? 'border-primary ring-2 ring-primary/25' : 'border-rail',
      )}
    >
      <span className="absolute inset-y-0 left-0 w-[3px]" style={{ background: accent }} aria-hidden />

      <Handle
        type="target"
        position={Position.Top}
        className="!h-2.5 !w-2.5 !border-2 !border-card"
        style={{ background: accent }}
      />

      <div className="flex items-center gap-2 px-3 py-2 pl-4">
        <span className="flex-shrink-0 text-base leading-none">🔀</span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-xs font-medium text-foreground">
            {String(d.label || t('workflows.nodeTypes.branch.label'))}
          </div>
          <div className="truncate font-mono text-[10px] text-muted-foreground">
            {hasConditions ? t('workflows.nodeStatus.conditionsSet') : t('workflows.nodeStatus.noConditions')}
          </div>
        </div>
      </div>

      <div className="flex items-center justify-between border-t border-rail px-3 py-1.5 pl-4 font-mono text-[9px] uppercase tracking-[0.08em]">
        <span className="text-primary">{t('workflows.nodeConfig.branchTrue')}</span>
        <span className="text-muted-foreground">{t('workflows.nodeConfig.branchFalse')}</span>
      </div>

      <Handle
        type="source"
        position={Position.Bottom}
        id="true"
        className="!h-2.5 !w-2.5 !border-2 !border-card !bg-primary"
        style={{ left: '30%' }}
      />
      <Handle
        type="source"
        position={Position.Bottom}
        id="false"
        className="!h-2.5 !w-2.5 !border-2 !border-card"
        style={{ left: '70%', background: accent }}
      />
    </div>
  );
}

export default memo(BranchNode);
