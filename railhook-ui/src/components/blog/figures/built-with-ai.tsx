import { useTranslation } from 'react-i18next';
import { CHROME, SERIES } from '../../charts/chartTheme';
import { cn } from '../../../lib/utils';
import { AXIS, Figure, LABEL, MONO, SOFT } from '../figures';


function useFigureText(key: string) {
  const { t } = useTranslation();
  return (path: string) => t(`blog.figures.${key}.${path}`);
}

type Owner = 'human' | 'agent' | 'machine';

const OWNER_STROKE: Record<Owner, { stroke: string; width: number; dash?: string }> = {
  human: { stroke: SERIES.brand, width: 2 },
  agent: { stroke: CHROME.ink, width: 1 },
  machine: { stroke: CHROME.muted, width: 1, dash: '4 3' },
};

function HarnessLoop() {
  const f = useFigureText('aiHarnessLoop');
  const width = 164;
  const height = 70;
  const gap = 20;
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
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 740 400">
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
            <text x={left + 10} y={box.y + 22} fill={CHROME.ink} className={cn(LABEL, 'font-medium')}>
              {f(`box.${box.key}.title`)}
            </text>
            <text x={left + 10} y={box.y + 41} fill={SOFT} className={MONO}>
              {f(`box.${box.key}.line1`)}
            </text>
            <text x={left + 10} y={box.y + 57} fill={SOFT} className={MONO}>
              {f(`box.${box.key}.line2`)}
            </text>
          </g>
        );
      })}

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

      <line
        x1={x(3) + 40}
        y1={rowTop + height + 2}
        x2={x(3) + 40}
        y2={rowBottom - 3}
        stroke={CHROME.muted}
        markerEnd="url(#ai-loop-arrow)"
      />
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
      <text x={x(2) + width / 2 + 6} y={rowBottom - 42} fill={SERIES.retry} className={cn(MONO, 'font-medium')}>
        {f('refused')}
      </text>

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
        className={cn(LABEL, 'font-medium')}
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
        d={`M ${incident.x + incident.width} ${incident.y + incident.height / 2} L 732 ${incident.y + incident.height / 2} L 732 48 L ${x(1) + width / 2} 48 L ${x(1) + width / 2} ${rowTop - 3}`}
        fill="none"
        stroke={SERIES.brand}
        strokeWidth={1.5}
        markerEnd="url(#ai-loop-arrow-brand)"
      />
      <text x={724} y={incident.y + incident.height / 2 - 8} textAnchor="end" fill={SERIES.brand} className={cn(MONO, 'font-medium')}>
        {f('feedback')}
      </text>
    </Figure>
  );
}

const BUGS = ['compressed', 'copied', 'native', 'squash', 'upgrade', 'zoom'];

function BugGuardrail() {
  const f = useFigureText('aiBugGuardrail');
  const top = 54;
  const row = 50;
  const columns = { bug: 16, caught: 300, guard: 486 };

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox={`0 0 720 ${top + BUGS.length * row + 8}`}>
      <defs>
        <marker id="ai-bug-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
          <path d="M0,0 L8,4 L0,8 z" fill={SERIES.brand} />
        </marker>
      </defs>
      {(['bug', 'caught', 'guard'] as const).map((column) => (
        <text key={column} x={columns[column]} y={30} fill={SOFT} className={cn(MONO, 'uppercase tracking-wider')}>
          {f(`head.${column}`)}
        </text>
      ))}
      <line x1={16} y1={top - 12} x2={704} y2={top - 12} {...AXIS} />

      {BUGS.map((bug, index) => {
        const y = top + index * row;
        return (
          <g key={bug}>
            <text x={columns.bug} y={y + 12} fill={CHROME.ink} className={cn(LABEL, 'font-medium')}>
              {f(`row.${bug}.bug`)}
            </text>
            <text x={columns.bug} y={y + 28} fill={SOFT} className={MONO}>
              {f(`row.${bug}.detail`)}
            </text>
            <text x={columns.caught} y={y + 12} fill={SOFT} className={MONO}>
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
            <text x={columns.guard} y={y + 12} fill={SERIES.brand} className={cn(MONO, 'font-medium')}>
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
  'ai-harness-loop': HarnessLoop,
  'ai-bug-guardrail': BugGuardrail,
};
