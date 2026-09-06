import { memo, type ReactNode } from 'react';
import { Handle, Position } from '@xyflow/react';
import { cn } from '../../../lib/utils';
import { NODE_ROLE_COLOR, type NodeRole } from './nodeTypes';

interface BaseNodeProps {
  role: NodeRole;
  icon: string;
  label: string;
  subtitle?: string;
  selected?: boolean;
  hasInput?: boolean;
  hasOutput?: boolean;
  children?: ReactNode;
}

/**
 * One surface for every node: `bg-card` on `border-rail`, like every other
 * container in the product. The only colour is the role rail down the left
 * edge, and the only emphasis is the brand ring on the selected node — so a
 * canvas of nine node types still reads as one quiet system.
 *
 * The subtitle is always a machine fact (an event type, a URL, an endpoint id,
 * a delay), so it is set in mono.
 */
function BaseNodeComponent({ role, icon, label, subtitle, selected, hasInput = true, hasOutput = true, children }: BaseNodeProps) {
  const accent = NODE_ROLE_COLOR[role];

  return (
    <div
      className={cn(
        'relative min-w-[180px] max-w-[240px] overflow-hidden rounded-lg border bg-card shadow-card transition-colors',
        selected ? 'border-primary ring-2 ring-primary/25' : 'border-rail',
      )}
    >
      <span className="absolute inset-y-0 left-0 w-[3px]" style={{ background: accent }} aria-hidden />

      {hasInput && (
        <Handle
          type="target"
          position={Position.Top}
          className="!h-2.5 !w-2.5 !border-2 !border-card"
          style={{ background: accent }}
        />
      )}

      <div className="flex items-center gap-2 px-3 py-2 pl-4">
        <span className="flex-shrink-0 text-base leading-none">{icon}</span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-xs font-medium text-foreground">{label}</div>
          {subtitle && <div className="truncate font-mono text-[10px] text-muted-foreground">{subtitle}</div>}
        </div>
      </div>

      {children && (
        <div className="border-t border-rail px-3 py-2 pl-4 text-[10px] text-muted-foreground">{children}</div>
      )}

      {hasOutput && (
        <Handle
          type="source"
          position={Position.Bottom}
          className="!h-2.5 !w-2.5 !border-2 !border-card"
          style={{ background: accent }}
        />
      )}
    </div>
  );
}

export default memo(BaseNodeComponent);
