import { useTranslation } from 'react-i18next';
import { CHROME, SERIES } from '../../charts/chartTheme';
import { cn } from '../../../lib/utils';
import { Figure } from '../figures';

/**
 * The figures of "building production software with AI agents".
 *
 * Every number drawn here was measured from this repository's own git history on the day the
 * post was written, and the post's table says how. They are literals on purpose: the drawing
 * records what the history said then, and a post is not a dashboard.
 */

const AXIS = { stroke: CHROME.rail, strokeWidth: 1 };
const LABEL = 'text-[11px]';
const MONO = 'font-mono text-[10px]';

function useFigureText(key: string) {
  const { t } = useTranslation();
  return (path: string, values?: Record<string, unknown>) => t(`blog.figures.${key}.${path}`, values);
}

/**
 * Non-merge commits per month, split by whether they carry a `Co-Authored-By: Claude` trailer,
 * with the release tags created that month underneath. `git log --no-merges` and
 * `git for-each-ref refs/tags`, December 2025 to September 2026.
 */
const MONTHS: { total: number; trailer: number; tags: number }[] = [
  { total: 66, trailer: 0, tags: 1 },
  { total: 0, trailer: 0, tags: 0 },
  { total: 127, trailer: 0, tags: 4 },
  { total: 152, trailer: 0, tags: 4 },
  { total: 0, trailer: 0, tags: 0 },
  { total: 0, trailer: 0, tags: 0 },
  { total: 0, trailer: 0, tags: 0 },
  { total: 0, trailer: 0, tags: 0 },
  { total: 323, trailer: 230, tags: 9 },
  { total: 420, trailer: 418, tags: 42 },
];

function CommitHistory() {
  const f = useFigureText('aiCommitHistory');
  const baseline = 222;
  const top = 64;
  const max = 420;
  const slot = 62;
  const width = 36;
  const x0 = 104;
  const scale = (n: number) => (n / max) * (baseline - top);

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 300">
      {/* Legend */}
      <rect x={16} y={14} width={10} height={10} rx={2} fill={SERIES.brand} />
      <text x={32} y={23} fill={CHROME.ink} className={LABEL}>
        {f('legend.trailer')}
      </text>
      <rect x={16} y={32} width={10} height={10} rx={2} fill={SERIES.brand} opacity={0.3} />
      <text x={32} y={41} fill={CHROME.ink} className={LABEL}>
        {f('legend.other')}
      </text>

      <line x1={x0 - 14} y1={baseline} x2={706} y2={baseline} {...AXIS} />
      <text x={16} y={baseline + 16} fill={CHROME.muted} className={MONO}>
        {f('monthRow')}
      </text>
      <text x={16} y={baseline + 50} fill={CHROME.muted} className={MONO}>
        {f('releasesRow')}
      </text>

      {MONTHS.map((month, index) => {
        const x = x0 + index * slot;
        const trailer = scale(month.trailer);
        const other = scale(month.total - month.trailer);
        const centre = x + width / 2;
        return (
          <g key={index}>
            {other > 0 && (
              <rect x={x} y={baseline - trailer - other} width={width} height={other} fill={SERIES.brand} opacity={0.3} />
            )}
            {trailer > 0 && <rect x={x} y={baseline - trailer} width={width} height={trailer} fill={SERIES.brand} />}
            <text
              x={centre}
              y={baseline - trailer - other - 6}
              textAnchor="middle"
              fill={month.total ? CHROME.ink : CHROME.muted}
              className={cn(MONO, month.total && 'font-semibold')}
            >
              {month.total}
            </text>
            <text x={centre} y={baseline + 16} textAnchor="middle" fill={CHROME.muted} className={MONO}>
              {f(`month.${index}`)}
            </text>
            <text
              x={centre}
              y={baseline + 50}
              textAnchor="middle"
              fill={month.tags ? CHROME.ink : CHROME.muted}
              className={cn(MONO, month.tags && 'font-semibold')}
            >
              {month.tags}
            </text>
          </g>
        );
      })}
      <text x={x0 + width / 2} y={baseline + 30} textAnchor="middle" fill={CHROME.muted} className={MONO}>
        2025
      </text>
      <text x={x0 + slot + width / 2} y={baseline + 30} textAnchor="middle" fill={CHROME.muted} className={MONO}>
        2026
      </text>

      {/* Where the trailer, and the harness, begin. */}
      <line
        x1={x0 + 8 * slot - 8}
        y1={top - 22}
        x2={x0 + 8 * slot - 8}
        y2={baseline}
        stroke={CHROME.muted}
        strokeDasharray="3 3"
      />
      <text x={x0 + 8 * slot - 14} y={top - 26} textAnchor="end" fill={CHROME.ink} className={cn(MONO, 'font-semibold')}>
        {f('harness')}
      </text>
    </Figure>
  );
}

/**
 * The engineering loop around the agent. Each box says who owns the step: the person, the agent,
 * or a machine check.
 */
type Owner = 'human' | 'agent' | 'machine';

const OWNER_STROKE: Record<Owner, { stroke: string; width: number; dash?: string }> = {
  human: { stroke: SERIES.brand, width: 2 },
  agent: { stroke: CHROME.ink, width: 1 },
  machine: { stroke: CHROME.muted, width: 1, dash: '4 3' },
};

function HarnessLoop() {
  const f = useFigureText('aiHarnessLoop');
  const width = 152;
  const height = 70;
  const gap = 23;
  const x = (column: number) => 16 + column * (width + gap);
  const rowTop = 64;
  const rowBottom = 232;
  const boxes: { key: string; column: number; y: number; owner: Owner }[] = [
    { key: 'task', column: 0, y: rowTop, owner: 'human' },
    { key: 'rules', column: 1, y: rowTop, owner: 'agent' },
    { key: 'test', column: 2, y: rowTop, owner: 'agent' },
    { key: 'code', column: 3, y: rowTop, owner: 'agent' },
    { key: 'local', column: 3, y: rowBottom, owner: 'machine' },
    { key: 'ci', column: 2, y: rowBottom, owner: 'machine' },
    { key: 'deploy', column: 1, y: rowBottom, owner: 'human' },
    { key: 'verify', column: 0, y: rowBottom, owner: 'machine' },
  ];
  const incident = { x: x(1), y: 344, width: 2 * width + gap, height: 40 };

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 400">
      <defs>
        <marker id="ai-loop-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
          <path d="M0,0 L8,4 L0,8 z" fill={CHROME.muted} />
        </marker>
        <marker id="ai-loop-arrow-retry" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
          <path d="M0,0 L8,4 L0,8 z" fill={SERIES.retry} />
        </marker>
        <marker id="ai-loop-arrow-brand" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
          <path d="M0,0 L8,4 L0,8 z" fill={SERIES.brand} />
        </marker>
      </defs>

      {/* Legend: who owns a step. */}
      {(['human', 'agent', 'machine'] as Owner[]).map((owner, index) => (
        <g key={owner} transform={`translate(${16 + index * 180}, 14)`}>
          <rect
            width={22}
            height={12}
            rx={3}
            fill="none"
            stroke={OWNER_STROKE[owner].stroke}
            strokeWidth={OWNER_STROKE[owner].width}
            strokeDasharray={OWNER_STROKE[owner].dash}
          />
          <text x={30} y={10} fill={CHROME.ink} className={LABEL}>
            {f(`owner.${owner}`)}
          </text>
        </g>
      ))}

      {boxes.map((box) => {
        const style = OWNER_STROKE[box.owner];
        const left = x(box.column);
        return (
          <g key={box.key}>
            <rect
              x={left}
              y={box.y}
              width={width}
              height={height}
              rx={9}
              fill={CHROME.surface}
              stroke={style.stroke}
              strokeWidth={style.width}
              strokeDasharray={style.dash}
            />
            <text x={left + 12} y={box.y + 22} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
              {f(`box.${box.key}.title`)}
            </text>
            <text x={left + 12} y={box.y + 41} fill={CHROME.muted} className={MONO}>
              {f(`box.${box.key}.line1`)}
            </text>
            <text x={left + 12} y={box.y + 56} fill={CHROME.muted} className={MONO}>
              {f(`box.${box.key}.line2`)}
            </text>
          </g>
        );
      })}

      {/* The top row runs left to right, the bottom row right to left. */}
      {[0, 1, 2].map((column) => (
        <line
          key={`top-${column}`}
          x1={x(column) + width + 2}
          y1={rowTop + height / 2}
          x2={x(column + 1) - 3}
          y2={rowTop + height / 2}
          stroke={CHROME.muted}
          markerEnd="url(#ai-loop-arrow)"
        />
      ))}
      {[3, 2, 1].map((column) => (
        <line
          key={`bottom-${column}`}
          x1={x(column) - 2}
          y1={rowBottom + height / 2}
          x2={x(column - 1) + width + 3}
          y2={rowBottom + height / 2}
          stroke={CHROME.muted}
          markerEnd="url(#ai-loop-arrow)"
        />
      ))}

      {/* Code goes down into the checks... */}
      <line
        x1={x(3) + 40}
        y1={rowTop + height + 2}
        x2={x(3) + 40}
        y2={rowBottom - 3}
        stroke={CHROME.muted}
        markerEnd="url(#ai-loop-arrow)"
      />
      {/* ...and a refusal from either check comes back up to the agent. */}
      <path
        d={`M ${x(2) + width / 2} ${rowBottom} L ${x(2) + width / 2} ${rowBottom - 34} L ${x(3) + 112} ${rowBottom - 34}`}
        fill="none"
        stroke={SERIES.retry}
        strokeWidth={1.5}
      />
      <line
        x1={x(3) + 112}
        y1={rowBottom}
        x2={x(3) + 112}
        y2={rowTop + height + 3}
        stroke={SERIES.retry}
        strokeWidth={1.5}
        markerEnd="url(#ai-loop-arrow-retry)"
      />
      <text x={x(2) + width / 2 + 6} y={rowBottom - 42} fill={SERIES.retry} className={cn(MONO, 'font-semibold')}>
        {f('refused')}
      </text>

      {/* What gets through anyway becomes a rule: the arrow that makes it a loop. */}
      <rect
        x={incident.x}
        y={incident.y}
        width={incident.width}
        height={incident.height}
        rx={9}
        fill="none"
        stroke={SERIES.brand}
        strokeWidth={2}
      />
      <text
        x={incident.x + incident.width / 2}
        y={incident.y + 25}
        textAnchor="middle"
        fill={CHROME.ink}
        className={cn(LABEL, 'font-semibold')}
      >
        {f('incident')}
      </text>
      <path
        d={`M ${x(0) + width / 2} ${rowBottom + height} L ${x(0) + width / 2} ${incident.y + incident.height / 2} L ${incident.x - 3} ${incident.y + incident.height / 2}`}
        fill="none"
        stroke={CHROME.muted}
        markerEnd="url(#ai-loop-arrow)"
      />
      <path
        d={`M ${incident.x + incident.width} ${incident.y + incident.height / 2} L 708 ${incident.y + incident.height / 2} L 708 48 L ${x(1) + width / 2} 48 L ${x(1) + width / 2} ${rowTop - 3}`}
        fill="none"
        stroke={SERIES.brand}
        strokeWidth={1.5}
        markerEnd="url(#ai-loop-arrow-brand)"
      />
      <text x={700} y={incident.y + incident.height / 2 - 8} textAnchor="end" fill={SERIES.brand} className={cn(MONO, 'font-semibold')}>
        {f('feedback')}
      </text>
    </Figure>
  );
}

/**
 * Ratchet test classes by the date their file was first added (`git log --follow
 * --diff-filter=A`), with the other parts of the harness marked underneath.
 */
const RATCHET_STEPS: { day: number; count: number }[] = [
  { day: 0, count: 0 },
  { day: 2, count: 6 },
  { day: 3, count: 10 },
  { day: 10, count: 11 },
  { day: 17, count: 12 },
  { day: 18, count: 19 },
  { day: 25, count: 20 },
  { day: 26, count: 21 },
  { day: 29, count: 22 },
  { day: 30, count: 22 },
];

const MILESTONES: { day: number; key: string; row: number; anchor: 'start' | 'end' }[] = [
  { day: 0, key: 'claude', row: 0, anchor: 'start' },
  { day: 2, key: 'context', row: 1, anchor: 'start' },
  { day: 23, key: 'deploy', row: 0, anchor: 'end' },
  { day: 24, key: 'e2e', row: 1, anchor: 'end' },
];

function RatchetGrowth() {
  const f = useFigureText('aiRatchetGrowth');
  const x0 = 56;
  const x1 = 690;
  const baseline = 196;
  const top = 44;
  const dayX = (day: number) => x0 + (day / 30) * (x1 - x0);
  const countY = (count: number) => baseline - (count / 22) * (baseline - top);

  const path = RATCHET_STEPS.map((step, index) => {
    if (index === 0) return `M ${dayX(step.day)} ${countY(step.count)}`;
    const previous = RATCHET_STEPS[index - 1];
    return `L ${dayX(step.day)} ${countY(previous.count)} L ${dayX(step.day)} ${countY(step.count)}`;
  }).join(' ');

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 300">
      <text x={x0} y={20} fill={CHROME.muted} className={LABEL}>
        {f('axis')}
      </text>
      {[0, 10, 20].map((count) => (
        <g key={count}>
          <line x1={x0} y1={countY(count)} x2={x1} y2={countY(count)} {...AXIS} strokeDasharray={count ? '2 4' : undefined} />
          <text x={x0 - 8} y={countY(count) + 3} textAnchor="end" fill={CHROME.muted} className={MONO}>
            {count}
          </text>
        </g>
      ))}
      {[
        { day: 0, key: 'start' },
        { day: 12, key: 'september' },
        { day: 30, key: 'end' },
      ].map((tick) => (
        <g key={tick.key}>
          <line x1={dayX(tick.day)} y1={baseline} x2={dayX(tick.day)} y2={baseline + 5} {...AXIS} />
          <text x={dayX(tick.day)} y={baseline + 17} textAnchor="middle" fill={CHROME.muted} className={MONO}>
            {f(`tick.${tick.key}`)}
          </text>
        </g>
      ))}

      <path d={path} fill="none" stroke={SERIES.brand} strokeWidth={2} />
      {RATCHET_STEPS.slice(1, -1).map((step) => (
        <circle key={step.day} cx={dayX(step.day)} cy={countY(step.count)} r={3} fill={SERIES.brand} />
      ))}
      <text x={dayX(30)} y={countY(22) - 8} textAnchor="end" fill={CHROME.ink} className={cn(MONO, 'font-semibold')}>
        22
      </text>
      <text x={dayX(18) + 8} y={countY(15)} fill={CHROME.ink} className={MONO}>
        {f('jump')}
      </text>

      {MILESTONES.map((milestone) => {
        const x = dayX(milestone.day);
        const y = baseline + 50 + milestone.row * 20;
        return (
          <g key={milestone.key}>
            <line x1={x} y1={baseline + 32} x2={x} y2={y - 4} stroke={CHROME.muted} strokeDasharray="2 2" />
            <circle cx={x} cy={baseline + 32} r={3} fill={CHROME.ink} />
            <text
              x={milestone.anchor === 'start' ? x - 2 : x + 2}
              y={y + 6}
              textAnchor={milestone.anchor}
              fill={CHROME.ink}
              className={MONO}
            >
              {f(`milestone.${milestone.key}`)}
            </text>
          </g>
        );
      })}
    </Figure>
  );
}

/** Six real bugs, how each was noticed, and the guardrail that now refuses it. */
const BUGS = ['compressed', 'copied', 'native', 'squash', 'upgrade', 'zoom'];

function BugGuardrail() {
  const f = useFigureText('aiBugGuardrail');
  const top = 54;
  const row = 50;
  const columns = { bug: 16, caught: 316, guard: 486 };

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox={`0 0 720 ${top + BUGS.length * row + 8}`}>
      <defs>
        <marker id="ai-bug-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
          <path d="M0,0 L8,4 L0,8 z" fill={SERIES.brand} />
        </marker>
      </defs>
      {(['bug', 'caught', 'guard'] as const).map((column) => (
        <text key={column} x={columns[column]} y={30} fill={CHROME.muted} className={cn(MONO, 'uppercase tracking-wider')}>
          {f(`head.${column}`)}
        </text>
      ))}
      <line x1={16} y1={top - 12} x2={704} y2={top - 12} {...AXIS} />

      {BUGS.map((bug, index) => {
        const y = top + index * row;
        return (
          <g key={bug}>
            <text x={columns.bug} y={y + 12} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
              {f(`row.${bug}.bug`)}
            </text>
            <text x={columns.bug} y={y + 28} fill={CHROME.muted} className={MONO}>
              {f(`row.${bug}.detail`)}
            </text>
            <text x={columns.caught} y={y + 12} fill={CHROME.muted} className={MONO}>
              {f(`row.${bug}.caught`)}
            </text>
            <line
              x1={columns.guard - 30}
              y1={y + 8}
              x2={columns.guard - 8}
              y2={y + 8}
              stroke={SERIES.brand}
              markerEnd="url(#ai-bug-arrow)"
            />
            <text x={columns.guard} y={y + 12} fill={SERIES.brand} className={cn(MONO, 'font-semibold')}>
              {f(`row.${bug}.guard`)}
            </text>
            <text x={columns.guard} y={y + 28} fill={SERIES.brand} className={MONO}>
              {f(`row.${bug}.guard2`)}
            </text>
            {index < BUGS.length - 1 && <line x1={16} y1={y + row - 12} x2={704} y2={y + row - 12} {...AXIS} strokeDasharray="2 4" />}
          </g>
        );
      })}
    </Figure>
  );
}

export const BUILT_WITH_AI_FIGURES: Record<string, () => JSX.Element> = {
  'ai-commit-history': CommitHistory,
  'ai-harness-loop': HarnessLoop,
  'ai-ratchet-growth': RatchetGrowth,
  'ai-bug-guardrail': BugGuardrail,
};
