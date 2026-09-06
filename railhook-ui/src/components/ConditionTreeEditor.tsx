/**
 * Visual Condition Tree Editor — shared between RulesPage and Workflow NodeConfigPanel.
 *
 * Renders a recursive AND/OR/NOT tree of conditions with dropdown operators,
 * field inputs and value inputs. No raw JSON editing needed.
 */
import { X, PlusCircle, FolderPlus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Input } from './ui/input';
import { Select } from './ui/select';
import type {
  ConditionNode, ConditionGroup, ConditionPredicate,
  PredicateOperator, GroupOperator,
} from '../api/rules.api';

// ─── Constants (exported for reuse) ─────────────────────────────

/**
 * The operator list, in the order a person scans it. The words live in the
 * locale bundle under `rules.operators.*` — an operator name is prose, so it
 * is translated, and only the symbol in front of it is not.
 */
export const OPERATORS: { value: PredicateOperator; symbol?: string }[] = [
  { value: 'EQ', symbol: '=' },
  { value: 'NEQ', symbol: '≠' },
  { value: 'GT', symbol: '>' },
  { value: 'GTE', symbol: '≥' },
  { value: 'LT', symbol: '<' },
  { value: 'LTE', symbol: '≤' },
  { value: 'BETWEEN', symbol: '↔' },
  { value: 'CONTAINS', symbol: '⊃' },
  { value: 'NOT_CONTAINS', symbol: '⊅' },
  { value: 'STARTS_WITH' },
  { value: 'ENDS_WITH' },
  { value: 'IN', symbol: '∈' },
  { value: 'NOT_IN', symbol: '∉' },
  { value: 'REGEX', symbol: '~' },
  { value: 'EXISTS', symbol: '∃' },
  { value: 'NOT_EXISTS', symbol: '∄' },
  { value: 'IS_NULL' },
  { value: 'NOT_NULL' },
];

export const NO_VALUE_OPS: PredicateOperator[] = ['EXISTS', 'NOT_EXISTS', 'IS_NULL', 'NOT_NULL'];

type Translate = (key: string) => string;

function operatorLabel(t: Translate, op: { value: PredicateOperator; symbol?: string }): string {
  const name = t(`rules.operators.${op.value}`);
  return op.symbol ? `${op.symbol} ${name}` : name;
}

// ─── Helpers (exported for reuse) ───────────────────────────────

export function mkGroup(op: GroupOperator = 'AND'): ConditionGroup {
  return { type: 'group', op, children: [] };
}

export function mkPredicate(): ConditionPredicate {
  return { type: 'predicate', field: '', operator: 'EQ', value: '', valueType: 'STRING' };
}

export function countPredicates(node: ConditionNode | null): number {
  if (!node) return 0;
  if (node.type === 'predicate') return 1;
  return node.children.reduce((s, c) => s + countPredicates(c), 0);
}

// ─── Props ──────────────────────────────────────────────────────

interface ConditionTreeEditorProps {
  node: ConditionNode;
  path?: number[];
  onChange: (updated: ConditionNode) => void;
  onRemove: () => void;
  depth?: number;
  /** Compact layout for narrow panels (workflow sidebar) */
  compact?: boolean;
}

// ─── Group Editor ───────────────────────────────────────────────

export default function ConditionTreeEditor({
  node,
  path = [],
  onChange,
  onRemove,
  depth = 0,
  compact = false,
}: ConditionTreeEditorProps) {
  const { t } = useTranslation();

  if (node.type === 'predicate') {
    return (
      <PredicateEditor
        node={node}
        onChange={onChange}
        onRemove={onRemove}
        compact={compact}
      />
    );
  }

  const cycleOp = () => {
    const ops: ConditionGroup['op'][] = ['AND', 'OR', 'NOT'];
    const idx = ops.indexOf(node.op);
    const next = ops[(idx + 1) % ops.length];
    onChange({ ...node, op: next });
  };

  const addChild = (child: ConditionNode) => {
    onChange({ ...node, children: [...node.children, child] });
  };

  const updateChild = (i: number, updated: ConditionNode) => {
    onChange({ ...node, children: node.children.map((c, j) => j === i ? updated : c) });
  };

  const removeChild = (i: number) => {
    const newChildren = node.children.filter((_, j) => j !== i);
    if (newChildren.length === 0 && depth > 0) {
      onRemove();
    } else {
      onChange({ ...node, children: newChildren });
    }
  };

  return (
    <div className="space-y-2 rounded-lg border-l-[3px] border-rail bg-muted/30 py-2 pl-3">
      {/* Group header */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={cycleOp}
          className="rounded bg-primary px-2.5 py-0.5 font-mono text-[11px] font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          title={t('rules.form.conditionTree.cycleOperator')}
          aria-label={t('rules.form.conditionTree.cycleOperator')}
        >
          {node.op}
        </button>
        <span className="text-[10px] text-muted-foreground">
          {t(`rules.form.conditionTree.groupHint.${node.op}`)}
        </span>
        <div className="flex-1" />
        <button
          onClick={() => addChild(mkPredicate())}
          className="p-1 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
          title={t('rules.form.conditionTree.addCondition')}
          aria-label={t('rules.form.conditionTree.addCondition')}
        >
          <PlusCircle className="h-3.5 w-3.5" />
        </button>
        <button
          onClick={() => addChild(mkGroup('AND'))}
          className="p-1 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
          title={t('rules.form.conditionTree.addGroup')}
          aria-label={t('rules.form.conditionTree.addGroup')}
        >
          <FolderPlus className="h-3.5 w-3.5" />
        </button>
        {depth > 0 && (
          <button
            onClick={onRemove}
            className="p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-colors"
            title={t('rules.form.conditionTree.removeGroup')}
            aria-label={t('rules.form.conditionTree.removeGroup')}
          >
            <X className="h-3.5 w-3.5" />
          </button>
        )}
      </div>

      {/* Children */}
      {node.children.length === 0 ? (
        <p className="pl-1 text-[11px] italic text-muted-foreground">{t('rules.form.conditionTree.emptyGroup')}</p>
      ) : (
        <div className="space-y-2">
          {node.children.map((child, i) => (
            <ConditionTreeEditor
              key={i}
              node={child}
              path={[...path, i]}
              onChange={(updated) => updateChild(i, updated)}
              onRemove={() => removeChild(i)}
              depth={depth + 1}
              compact={compact}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ─── Predicate Editor (single condition row) ────────────────────

function PredicateEditor({
  node,
  onChange,
  onRemove,
  compact,
}: {
  node: ConditionPredicate;
  onChange: (updated: ConditionNode) => void;
  onRemove: () => void;
  compact?: boolean;
}) {
  const { t } = useTranslation();
  const needsValue = !NO_VALUE_OPS.includes(node.operator);

  const handleValueChange = (raw: string) => {
    const num = Number(raw);
    if (raw !== '' && !isNaN(num)) {
      onChange({ ...node, value: num, valueType: 'NUMBER' });
    } else if (raw === 'true' || raw === 'false') {
      onChange({ ...node, value: raw === 'true', valueType: 'BOOLEAN' });
    } else {
      onChange({ ...node, value: raw, valueType: 'STRING' });
    }
  };

  // Compact layout: stack vertically for narrow sidebar
  if (compact) {
    return (
      <div className="bg-muted/40 rounded-lg p-2 border space-y-1.5">
        <div className="flex items-center gap-1">
          <Input
            placeholder="data.amount"
            value={node.field}
            onChange={(e) => onChange({ ...node, field: e.target.value })}
            className="font-mono text-xs h-7 flex-1"
          />
          <button
            onClick={onRemove}
            className="p-1 rounded-md hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-colors shrink-0"
            title={t('rules.form.conditionTree.removeCondition')}
            aria-label={t('rules.form.conditionTree.removeCondition')}
          >
            <X className="h-3 w-3" />
          </button>
        </div>
        <div className={`grid gap-1.5 ${needsValue ? 'grid-cols-2' : 'grid-cols-1'}`}>
          <Select
            value={node.operator}
            onChange={(e) => onChange({ ...node, operator: e.target.value as PredicateOperator })}
            className="h-7 text-xs"
          >
            {OPERATORS.map(op => (
              <option key={op.value} value={op.value}>{operatorLabel(t, op)}</option>
            ))}
          </Select>
          {needsValue && (
            <Input
              placeholder={t('rules.form.valuePlaceholder')}
              value={node.value === undefined || node.value === null ? '' : String(node.value)}
              onChange={(e) => handleValueChange(e.target.value)}
              className="h-7 text-xs"
            />
          )}
        </div>
      </div>
    );
  }

  // Wide layout: horizontal row
  return (
    <div className="flex items-start gap-2 bg-muted/40 rounded-lg p-2.5 border">
      <div className={`flex-1 grid gap-2 ${needsValue ? 'grid-cols-3' : 'grid-cols-2'}`}>
        <Input
          placeholder="payload.data.amount"
          value={node.field}
          onChange={(e) => onChange({ ...node, field: e.target.value })}
          className="font-mono text-xs h-8"
        />
        <Select
          value={node.operator}
          onChange={(e) => onChange({ ...node, operator: e.target.value as PredicateOperator })}
          className="h-8 text-xs"
        >
          {OPERATORS.map(op => (
            <option key={op.value} value={op.value}>{operatorLabel(t, op)}</option>
          ))}
        </Select>
        {needsValue && (
          <Input
            placeholder={t('rules.form.valuePlaceholder')}
            value={node.value === undefined || node.value === null ? '' : String(node.value)}
            onChange={(e) => handleValueChange(e.target.value)}
            className="h-8 text-xs"
          />
        )}
      </div>
      <button
        onClick={onRemove}
        className="p-1.5 rounded-md hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-colors mt-0.5"
        title={t('rules.form.conditionTree.removeCondition')}
        aria-label={t('rules.form.conditionTree.removeCondition')}
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}
