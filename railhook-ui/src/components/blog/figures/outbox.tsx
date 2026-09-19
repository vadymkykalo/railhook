import { useTranslation } from 'react-i18next';
import { CHROME, SERIES } from '../../charts/chartTheme';
import { cn } from '../../../lib/utils';
import { Figure, LABEL, MONO, SOFT, wrapWords } from '../figures';

/**
 * The drawings for the transactional outbox post: the three delivery semantics, the dual write,
 * the pipeline an Event travels, and a failed Delivery holding the ones behind it.
 *
 * Same rules as the figures beside them: tokens only, `ok` / `retry` / `halt` / `idle` for what
 * they name — a parked Delivery is `idle` because a Deferral tries nothing — and every word a key
 * under `blog.figures.<key>`. Drawn on a narrower canvas than the older figures, so that on a
 * phone the type shrinks less.
 */

const NARROW = 'min-w-[520px]';

type Tone = 'ok' | 'halt' | 'brand' | 'skipped';

function toneColour(tone: Tone): string {
  if (tone === 'ok') return SERIES.ok;
  if (tone === 'halt') return SERIES.halt;
  if (tone === 'brand') return SERIES.brand;
  return CHROME.muted;
}

/** A cross, for the moment something died. */
function Cross({ x, y, size = 6 }: { x: number; y: number; size?: number }) {
  return (
    <g transform={`translate(${x}, ${y})`}>
      <line x1={-size} y1={-size} x2={size} y2={size} stroke={SERIES.halt} strokeWidth={2.2} />
      <line x1={-size} y1={size} x2={size} y2={-size} stroke={SERIES.halt} strokeWidth={2.2} />
    </g>
  );
}

function Arrowhead({ id, colour }: { id: string; colour: string }) {
  return (
    <marker id={id} markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
      <path d="M0,0 L8,4 L0,8 z" fill={colour} />
    </marker>
  );
}

/**
 * Three orderings of the same two writes, and the same crash dropped into each.
 *
 * The crash is at the same position in every lane on purpose: the point is not that crashes are
 * likely, it is that only one ordering survives one wherever it lands.
 */
function DualWrite() {
  const { t } = useTranslation();
  const f = (key: string) => t(`blog.figures.dualWrite.${key}`);
  const xs = [88, 232, 376, 520];
  const lanes: { key: string; y: number; steps: Tone[]; crashAt: number; outcome: Tone }[] = [
    { key: 'commitFirst', y: 78, steps: ['ok', 'brand', 'halt', 'skipped'], crashAt: 2, outcome: 'halt' },
    { key: 'publishFirst', y: 188, steps: ['brand', 'halt', 'halt', 'brand'], crashAt: 1, outcome: 'halt' },
    { key: 'outbox', y: 298, steps: ['ok', 'brand', 'halt', 'ok'], crashAt: 2, outcome: 'ok' },
  ];

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 620 352" className={NARROW}>
      {lanes.map((lane) => (
        <g key={lane.key}>
          <text x={16} y={lane.y - 44} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
            {f(`${lane.key}.title`)}
          </text>
          <text
            x={604}
            y={lane.y - 44}
            textAnchor="end"
            fill={toneColour(lane.outcome)}
            className={cn(MONO, 'font-semibold')}
          >
            {f(`${lane.key}.outcome`)}
          </text>
          <line x1={xs[0]} y1={lane.y} x2={xs[3]} y2={lane.y} stroke={CHROME.rail} strokeWidth={1.5} />
          {lane.steps.map((tone, index) => (
            <g key={index}>
              {index === lane.crashAt ? (
                <Cross x={xs[index]} y={lane.y} />
              ) : tone === 'skipped' ? (
                <circle
                  cx={xs[index]}
                  cy={lane.y}
                  r={6}
                  fill={CHROME.surface}
                  stroke={CHROME.muted}
                  strokeWidth={1.2}
                  strokeDasharray="2 2"
                />
              ) : (
                <circle cx={xs[index]} cy={lane.y} r={6} fill={toneColour(tone)} />
              )}
              <text
                x={xs[index]}
                y={lane.y - 14}
                textAnchor="middle"
                fill={tone === 'skipped' ? SOFT : CHROME.ink}
                className={LABEL}
              >
                {f(`${lane.key}.step${index}`)}
              </text>
              <text x={xs[index]} y={lane.y + 22} textAnchor="middle" fill={SOFT} className={MONO}>
                {/* Wrapped: four notes share a 620-wide row, and a Ukrainian one runs to 25 characters. */}
                {wrapWords(f(`${lane.key}.note${index}`), 20).map((part, row) => (
                  <tspan key={row} x={xs[index]} dy={row === 0 ? 0 : 15}>
                    {part}
                  </tspan>
                ))}
              </text>
            </g>
          ))}
        </g>
      ))}
      <line x1={16} y1={124} x2={604} y2={124} stroke={CHROME.rail} strokeWidth={1} strokeDasharray="2 4" />
      <line x1={16} y1={234} x2={604} y2={234} stroke={CHROME.rail} strokeWidth={1} strokeDasharray="2 4" />
    </Figure>
  );
}

/**
 * The path an Event takes, as the code draws it: one transaction, a relay, a broker keyed by
 * Endpoint, and a worker that proves ownership against the same Postgres rows before it sends.
 */
function OutboxPipeline() {
  const { t } = useTranslation();
  const f = (key: string) => t(`blog.figures.outboxPipeline.${key}`);
  const arrow = 'url(#outbox-pipeline-arrow)';

  const box = (x: number, y: number, width: number, height: number, key: string, strong = false) => (
    <g>
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        rx={10}
        fill="none"
        stroke={strong ? SERIES.brand : CHROME.rail}
        strokeWidth={strong ? 1.5 : 1}
      />
      <text x={x + 14} y={y + 22} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
        {f(`${key}.title`)}
      </text>
    </g>
  );

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 620 346" className={NARROW}>
      <defs>
        <Arrowhead id="outbox-pipeline-arrow" colour={CHROME.muted} />
      </defs>

      {/* Row one: accepted. */}
      {box(8, 20, 158, 118, 'api')}
      <text x={22} y={62} fill={SOFT} className={MONO}>
        {f('api.line0')}
      </text>
      <text x={22} y={78} fill={SOFT} className={MONO}>
        {f('api.line1')}
      </text>
      <text x={22} y={116} fill={SERIES.brand} className={cn(MONO, 'font-semibold')}>
        {f('api.line2')}
      </text>

      {box(200, 20, 212, 150, 'postgres', true)}
      <image href="/logos/brand/postgresql.svg" x={384} y={28} width={18} height={18} />
      <text x={212} y={58} fill={SOFT} className={MONO}>
        {f('postgres.tx')}
      </text>
      {['events', 'deliveries', 'outbox'].map((row, index) => (
        <g key={row}>
          <rect
            x={212}
            y={68 + index * 30}
            width={186}
            height={22}
            rx={4}
            fill={SERIES.brand}
            opacity={row === 'outbox' ? 0.2 : 0.08}
          />
          <text x={222} y={83 + index * 30} fill={CHROME.ink} className={MONO}>
            {f(`postgres.${row}`)}
          </text>
        </g>
      ))}

      {box(440, 20, 172, 118, 'publisher')}
      {['line0', 'line1', 'line2'].map((line, index) => (
        <text key={line} x={450} y={62 + index * 17} fill={SOFT} className={MONO}>
          {f(`publisher.${line}`)}
        </text>
      ))}

      {/* Row two: announced, then attempted. */}
      {box(440, 222, 172, 108, 'kafka')}
      {/* The Kafka mark has no light version, so on ink it sits on a light tile, as on the landing page. */}
      <rect x={584} y={228} width={20} height={20} rx={4} className="fill-transparent dark:fill-foreground" />
      <image href="/logos/brand/apachekafka.svg" x={586} y={230} width={16} height={16} />
      <text x={450} y={264} fill={SOFT} className={MONO}>
        {f('kafka.line0')}
      </text>
      <text x={450} y={281} fill={SOFT} className={MONO}>
        {f('kafka.line1')}
      </text>

      {box(200, 222, 212, 108, 'worker', true)}
      <text x={212} y={264} fill={SOFT} className={MONO}>
        {f('worker.line0')}
      </text>
      <text x={212} y={281} fill={SOFT} className={MONO}>
        {f('worker.line1')}
      </text>
      <text x={212} y={310} fill={SERIES.retry} className={cn(MONO, 'font-semibold')}>
        {f('worker.line2')}
      </text>

      {box(8, 222, 158, 108, 'endpoint')}
      <text x={22} y={264} fill={SOFT} className={MONO}>
        {f('endpoint.line0')}
      </text>
      <text x={22} y={280} fill={SOFT} className={MONO}>
        {f('endpoint.line1')}
      </text>
      <text x={22} y={296} fill={SOFT} className={MONO}>
        {f('endpoint.line2')}
      </text>

      {/* The arrows, in the order the Event travels them. */}
      <line x1={166} y1={79} x2={196} y2={79} stroke={CHROME.muted} markerEnd={arrow} />
      <line x1={412} y1={79} x2={436} y2={79} stroke={CHROME.muted} markerEnd={arrow} />
      <line x1={532} y1={138} x2={532} y2={218} stroke={CHROME.muted} markerEnd={arrow} />
      <text x={540} y={184} fill={SOFT} className={MONO}>
        {f('arrow.produce')}
      </text>
      <line x1={440} y1={276} x2={416} y2={276} stroke={CHROME.muted} markerEnd={arrow} />
      <line x1={200} y1={276} x2={170} y2={276} stroke={CHROME.muted} markerEnd={arrow} />

      {/* The worker proves ownership against the rows the API wrote. */}
      <line x1={290} y1={218} x2={290} y2={174} stroke={SERIES.brand} strokeWidth={1.5} markerEnd={arrow} />
      <text x={298} y={200} fill={SERIES.brand} className={cn(MONO, 'font-semibold')}>
        {f('arrow.claim')}
      </text>
    </Figure>
  );
}

/** Seconds onto the ordering timeline. */
const ORDER_X0 = 88;
const ORDER_X1 = 560;
const ORDER_SPAN = 90;

function secondsX(seconds: number): number {
  return ORDER_X0 + (seconds / ORDER_SPAN) * (ORDER_X1 - ORDER_X0);
}

/**
 * Four ordered Deliveries to one Endpoint, the first of which fails once.
 *
 * Illustrative rather than measured: the retry lands inside its jitter window (30–90s for the
 * first rung) and the successors go in turn once the cursor moves. What is not illustrative is the
 * marker — the gap timeout is measured from when the first successor was parked, and past it the
 * successors stop waiting.
 */
function OrderingHold() {
  const { t } = useTranslation();
  const f = (key: string) => t(`blog.figures.orderingHold.${key}`);
  const lanes = [50, 96, 142, 188];
  const retryAt = 44;
  const arrivals = [3, 6, 9];
  const releases = [46, 48.5, 51];
  const gapTimeoutAt = arrivals[0] + 60;
  const axis = 226;

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 620 262" className={NARROW}>
      {lanes.map((y, index) => (
        <g key={y}>
          <line x1={ORDER_X0} y1={y} x2={ORDER_X1} y2={y} stroke={CHROME.rail} strokeWidth={1} strokeDasharray="2 4" />
          <text x={16} y={y + 4} fill={CHROME.ink} className={cn(MONO, 'font-semibold')}>
            {t('blog.figures.orderingHold.seq', { number: index + 1 })}
          </text>
        </g>
      ))}

      {/* Sequence 1: fails, waits on its ladder, succeeds. */}
      <rect
        x={secondsX(0)}
        y={lanes[0] - 7}
        width={secondsX(retryAt) - secondsX(0)}
        height={14}
        rx={7}
        fill={SERIES.retry}
        opacity={0.2}
      />
      <Cross x={secondsX(0)} y={lanes[0]} size={5} />
      <circle cx={secondsX(retryAt)} cy={lanes[0]} r={6} fill={SERIES.ok} />
      <text x={secondsX(0) + 10} y={lanes[0] - 12} fill={CHROME.ink} className={LABEL}>
        {f('first.fail')}
      </text>
      <text x={secondsX(retryAt)} y={lanes[0] - 12} textAnchor="middle" fill={CHROME.ink} className={LABEL}>
        {f('first.ok')}
      </text>
      <text x={(secondsX(0) + secondsX(retryAt)) / 2} y={lanes[0] + 4} textAnchor="middle" fill={SERIES.retry} className={MONO}>
        {f('first.wait')}
      </text>

      {/* Sequences 2–4: parked, then released one after another. */}
      {arrivals.map((arrival, index) => {
        const y = lanes[index + 1];
        return (
          <g key={arrival}>
            <rect
              x={secondsX(arrival)}
              y={y - 7}
              width={secondsX(releases[index]) - secondsX(arrival)}
              height={14}
              rx={7}
              fill={SERIES.idle}
              opacity={0.35}
            />
            <circle cx={secondsX(arrival)} cy={y} r={3} fill={SERIES.idle} />
            <circle cx={secondsX(releases[index])} cy={y} r={6} fill={SERIES.ok} />
          </g>
        );
      })}
      <text x={secondsX(arrivals[0]) + 8} y={lanes[1] - 12} fill={SOFT} className={MONO}>
        {f('parked')}
      </text>
      <text x={secondsX(releases[2]) + 8} y={lanes[3] + 24} textAnchor="end" fill={CHROME.ink} className={LABEL}>
        {f('released')}
      </text>

      {/* The gap timeout: where the hold would have given way. */}
      <line
        x1={secondsX(gapTimeoutAt)}
        y1={30}
        x2={secondsX(gapTimeoutAt)}
        y2={axis}
        stroke={SERIES.halt}
        strokeWidth={1.2}
        strokeDasharray="4 4"
      />
      <text x={secondsX(gapTimeoutAt) + 6} y={18} fill={SERIES.halt} className={cn(MONO, 'font-semibold')}>
        {f('timeout.title')}
      </text>
      <text x={secondsX(gapTimeoutAt) + 6} y={lanes[2] + 2} fill={SOFT} className={MONO}>
        {f('timeout.line0')}
      </text>
      <text x={secondsX(gapTimeoutAt) + 6} y={lanes[2] + 18} fill={SOFT} className={MONO}>
        {f('timeout.line1')}
      </text>

      <line x1={ORDER_X0} y1={axis} x2={ORDER_X1} y2={axis} stroke={CHROME.rail} strokeWidth={1} />
      {[0, 15, 30, 45, 60, 75, 90].map((seconds) => (
        <g key={seconds}>
          <line x1={secondsX(seconds)} y1={axis} x2={secondsX(seconds)} y2={axis + 5} stroke={CHROME.rail} />
          <text x={secondsX(seconds)} y={axis + 18} textAnchor="middle" fill={SOFT} className={MONO}>
            {t('blog.figures.orderingHold.tick', { seconds })}
          </text>
        </g>
      ))}
    </Figure>
  );
}

/**
 * The three delivery semantics against the two failures a sender cannot tell apart.
 *
 * Columns are what actually happened; the bracket over them is what the sender observed, which is
 * the same timeout in both. That identical observation is the whole argument against
 * exactly-once over HTTP, so it is drawn rather than stated.
 */
function DeliverySemantics() {
  const { t } = useTranslation();
  const f = (key: string) => t(`blog.figures.deliverySemantics.${key}`);
  const columns = [318, 488];
  const cellWidth = 150;
  const rows: { key: string; y: number; cells: [Tone, Tone] | null; strong?: boolean }[] = [
    { key: 'atMostOnce', y: 112, cells: ['halt', 'ok'] },
    { key: 'atLeastOnce', y: 160, cells: ['ok', 'halt'] },
    { key: 'exactlyOnce', y: 208, cells: null },
    { key: 'dedupe', y: 256, cells: ['ok', 'ok'], strong: true },
  ];

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 620 290" className={NARROW}>
      {/* What the sender saw: one bracket over both columns. */}
      <path
        d={`M ${columns[0] - cellWidth / 2} 30 L ${columns[0] - cellWidth / 2} 22 L ${columns[1] + cellWidth / 2} 22 L ${columns[1] + cellWidth / 2} 30`}
        fill="none"
        stroke={CHROME.muted}
        strokeWidth={1}
      />
      <text x={(columns[0] + columns[1]) / 2} y={14} textAnchor="middle" fill={CHROME.ink} className={cn(MONO, 'font-semibold')}>
        {f('sees')}
      </text>
      {['lost', 'ackLost'].map((column, index) => (
        <g key={column}>
          <text x={columns[index]} y={52} textAnchor="middle" fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
            {f(`${column}.title`)}
          </text>
          <text x={columns[index]} y={68} textAnchor="middle" fill={SOFT} className={MONO}>
            {f(`${column}.note`)}
          </text>
        </g>
      ))}
      <line x1={16} y1={84} x2={604} y2={84} stroke={CHROME.rail} strokeWidth={1} />

      {rows.map((row) => (
        <g key={row.key}>
          <text x={16} y={row.y - 2} fill={row.strong ? SERIES.brand : CHROME.ink} className={cn(LABEL, 'font-semibold')}>
            {f(`${row.key}.name`)}
          </text>
          <text x={16} y={row.y + 13} fill={SOFT} className={MONO}>
            {f(`${row.key}.rule`)}
          </text>
          {row.cells ? (
            row.cells.map((tone, index) => (
              <g key={index}>
                <rect
                  x={columns[index] - cellWidth / 2}
                  y={row.y - 15}
                  width={cellWidth}
                  height={26}
                  rx={13}
                  fill={toneColour(tone)}
                  opacity={0.14}
                />
                <text
                  x={columns[index]}
                  y={row.y + 2}
                  textAnchor="middle"
                  fill={toneColour(tone)}
                  className={cn(MONO, 'font-semibold')}
                >
                  {f(`${row.key}.cell${index}`)}
                </text>
              </g>
            ))
          ) : (
            <g>
              <rect
                x={columns[0] - cellWidth / 2}
                y={row.y - 15}
                width={columns[1] - columns[0] + cellWidth}
                height={26}
                rx={13}
                fill="none"
                stroke={CHROME.muted}
                strokeDasharray="3 3"
              />
              <text x={(columns[0] + columns[1]) / 2} y={row.y + 2} textAnchor="middle" fill={SOFT} className={MONO}>
                {f(`${row.key}.cell`)}
              </text>
            </g>
          )}
          {row.key !== 'dedupe' && <line x1={16} y1={row.y + 24} x2={604} y2={row.y + 24} stroke={CHROME.rail} strokeWidth={1} strokeDasharray="2 4" />}
        </g>
      ))}
    </Figure>
  );
}

/**
 * The one duplicate no sender can prevent: the receiver commits, and its answer never arrives.
 *
 * Two lanes and time running right, like a sequence diagram turned on its side. The worker's
 * lane says only what the worker can know, which is nothing between the request and the timeout.
 */
function LostAck() {
  const { t } = useTranslation();
  const f = (key: string) => t(`blog.figures.lostAck.${key}`);
  const arrow = 'url(#lost-ack-arrow)';
  const top = 70;
  const bottom = 196;

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 620 262" className={NARROW}>
      <defs>
        <Arrowhead id="lost-ack-arrow" colour={CHROME.muted} />
      </defs>

      {/* The two parties. */}
      <text x={16} y={top + 4} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
        {f('worker')}
      </text>
      <text x={16} y={bottom + 4} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
        {f('receiver')}
      </text>
      <line x1={116} y1={top} x2={604} y2={top} stroke={CHROME.rail} strokeWidth={1.5} />
      <line x1={116} y1={bottom} x2={604} y2={bottom} stroke={CHROME.rail} strokeWidth={1.5} />

      {/* Attempt 1 goes out. */}
      <circle cx={130} cy={top} r={5} fill={SERIES.brand} />
      <line x1={132} y1={top + 6} x2={176} y2={bottom - 8} stroke={CHROME.muted} markerEnd={arrow} />
      <text x={130} y={top - 14} fill={CHROME.ink} className={LABEL}>
        {f('send')}
      </text>

      {/* The receiver does the work and commits it. */}
      <rect x={180} y={bottom - 8} width={150} height={16} rx={8} fill={SERIES.ok} opacity={0.2} />
      <circle cx={330} cy={bottom} r={5} fill={SERIES.ok} />
      <text x={186} y={bottom + 28} fill={CHROME.ink} className={LABEL}>
        {f('commit')}
      </text>
      <text x={186} y={bottom + 43} fill={CHROME.muted} className={MONO}>
        {f('commitNote')}
      </text>

      {/* Its 200 dies on the way back. */}
      <line x1={334} y1={bottom - 8} x2={362} y2={140} stroke={CHROME.muted} strokeDasharray="4 3" />
      <Cross x={368} y={132} />
      <text x={354} y={128} textAnchor="end" fill={SERIES.halt} className={cn(MONO, 'font-semibold')}>
        {f('lost')}
      </text>

      {/* What the worker sees meanwhile: nothing, then a timeout. */}
      <line x1={136} y1={top} x2={452} y2={top} stroke={SERIES.retry} strokeWidth={2} strokeDasharray="2 4" />
      <text x={300} y={top + 18} textAnchor="middle" fill={CHROME.muted} className={MONO}>
        {f('silence')}
      </text>
      <circle cx={456} cy={top} r={5} fill={SERIES.retry} />
      <text x={456} y={top - 30} textAnchor="middle" fill={SERIES.retry} className={cn(MONO, 'font-semibold')}>
        {f('timeout')}
      </text>
      <text x={456} y={top - 16} textAnchor="middle" fill={CHROME.muted} className={MONO}>
        {f('question')}
      </text>

      {/* Attempt 2, same id: the receiver's dedupe is the only thing that can tell. */}
      <line x1={470} y1={top + 6} x2={514} y2={bottom - 8} stroke={CHROME.muted} markerEnd={arrow} />
      <text x={506} y={top + 34} fill={CHROME.ink} className={LABEL}>
        {f('retry')}
      </text>
      <text x={506} y={top + 49} fill={CHROME.ink} className={LABEL}>
        {f('retryId')}
      </text>
      <circle cx={520} cy={bottom} r={5} fill={SERIES.ok} />
      <text x={604} y={bottom + 28} textAnchor="end" fill={SERIES.ok} className={cn(MONO, 'font-semibold')}>
        {f('dedupe')}
      </text>
    </Figure>
  );
}

/** Registered into `FIGURES` in `../figures.tsx`. */
export const OUTBOX_FIGURES: Record<string, () => JSX.Element> = {
  'lost-ack': LostAck,
  'delivery-semantics': DeliverySemantics,
  'dual-write': DualWrite,
  'outbox-pipeline': OutboxPipeline,
  'ordering-hold': OrderingHold,
};
