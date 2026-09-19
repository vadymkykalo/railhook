import { useTranslation } from 'react-i18next';
import { CHROME, SERIES } from '../../charts/chartTheme';
import { cn } from '../../../lib/utils';
import { AXIS, Figure, LABEL, MONO, SOFT, wrapWords } from '../figures';

/**
 * The drawings for "Webhooks, explained", placed with `:::figure <key>`.
 *
 * Same rules as `../figures.tsx`: tokens only, `ok` / `retry` / `halt` / `idle` for what they
 * name and the brand cobalt for everything else, every word a key under `blog.figures.*`.
 *
 * The one exception to "every word a key" is wire data — header names, a message id, a
 * signature. Those are the bytes a request carries, identical in every language, so they are
 * constants here rather than strings a translator could helpfully change.
 */

/** A marker per figure: two figures on one page must not share an element id. */
function Arrow({ id, colour = CHROME.muted }: { id: string; colour?: string }) {
  return (
    <marker id={id} markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
      <path d="M0,0 L8,4 L0,8 z" fill={colour} />
    </marker>
  );
}

function useFigureText(key: string) {
  const { t } = useTranslation();
  return (path: string, values?: Record<string, unknown>) => t(`blog.figures.${key}.${path}`, values);
}

/** Polling asks on a timer and mostly hears "nothing"; a webhook speaks once, when it happens. */
function PollingVsPush() {
  const f = useFigureText('pollingVsPush');
  const X0 = 180;
  const X1 = 700;
  const eventX = 452;
  const polls = [200, 280, 360, 440, 520, 600, 680];
  const found = 520;
  const pollY = 86;
  const pushY = 196;

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 280">
      <defs>
        <Arrow id="wg-poll-arrow" />
      </defs>

      {/* The moment the order is created, through both lanes. */}
      <line x1={eventX} y1={30} x2={eventX} y2={236} stroke={CHROME.ink} strokeWidth={1} strokeDasharray="3 3" />
      <text x={eventX} y={22} textAnchor="middle" fill={CHROME.ink} className={cn(MONO, 'font-semibold')}>
        {f('event')}
      </text>

      {[pollY, pushY].map((y) => (
        <line key={y} x1={X0} y1={y} x2={X1} y2={y} {...AXIS} strokeDasharray="2 4" />
      ))}
      <line x1={X0} y1={236} x2={X1} y2={236} {...AXIS} markerEnd="url(#wg-poll-arrow)" />
      <text x={X1} y={256} textAnchor="end" fill={CHROME.muted} className={MONO}>
        {f('time')}
      </text>

      {/* Lane titles. */}
      <text x={12} y={pollY - 6} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
        {f('polling.title')}
      </text>
      <text x={12} y={pollY + 10} fill={CHROME.muted} className={MONO}>
        {f('polling.body')}
      </text>
      <text x={12} y={pushY - 6} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
        {f('push.title')}
      </text>
      <text x={12} y={pushY + 10} fill={CHROME.muted} className={MONO}>
        {f('push.body')}
      </text>

      {/* Polling: a request per interval. Empty answers are idle; one finds the order. */}
      {polls.map((x) => (
        <g key={x}>
          <line x1={x} y1={pollY - 18} x2={x} y2={pollY - 6} stroke={x === found ? SERIES.brand : SERIES.idle} strokeWidth={1.5} />
          <circle cx={x} cy={pollY} r={5} fill={x === found ? SERIES.brand : SERIES.idle} />
        </g>
      ))}
      <text x={polls[1]} y={pollY + 24} textAnchor="middle" fill={CHROME.muted} className={MONO}>
        {f('polling.empty')}
      </text>
      <text x={found} y={pollY - 24} textAnchor="middle" fill={SERIES.brand} className={cn(MONO, 'font-semibold')}>
        {f('polling.found')}
      </text>
      {/* The lag: from the event to the poll that noticed it. */}
      <path
        d={`M ${eventX} ${pollY + 16} L ${eventX} ${pollY + 22} L ${found} ${pollY + 22} L ${found} ${pollY + 16}`}
        fill="none"
        stroke={CHROME.ink}
        strokeWidth={1}
      />
      <text x={eventX + 6} y={pollY + 36} fill={CHROME.ink} className={MONO}>
        {f('polling.lag')}
      </text>
      <text x={X1} y={pollY + 52} textAnchor="end" fill={CHROME.muted} className={MONO}>
        {f('polling.tally')}
      </text>

      {/* Push: one request, at the moment it happens. */}
      <circle cx={eventX} cy={pushY} r={6} fill={SERIES.brand} />
      <text x={eventX + 14} y={pushY - 10} fill={SERIES.brand} className={cn(MONO, 'font-semibold')}>
        {f('push.request')}
      </text>
      <text x={X1} y={pushY + 24} textAnchor="end" fill={CHROME.muted} className={MONO}>
        {f('push.tally')}
      </text>
    </Figure>
  );
}

/** The wire literals of the example request, verbatim from the article's code block. */
const WIRE = [
  { text: 'POST /webhooks/orders HTTP/1.1', note: 'target' },
  { text: 'content-type: application/json', note: null },
  { text: 'webhook-id: msg_2mQ8hZk3Xv9aR1cT', note: 'id' },
  { text: 'webhook-timestamp: 1789983612', note: 'timestamp' },
  { text: 'webhook-signature: v1,pX/4CnjtyRIdKhtP…', note: 'signature' },
  { text: '', note: null },
  { text: '{ "type": "order.created",', note: 'body' },
  { text: '  "data": { "order_id": "ord_8412", … } }', note: null },
] as const;

/**
 * One webhook request, with what each part of it is for.
 *
 * Set larger than the other figures — it is read line by line, not glanced at. The request keeps
 * its own line spacing and the callouts keep theirs, joined by elbow leaders: three of the five
 * annotated lines are adjacent, and callouts pinned to them would stack into each other.
 */
function WebhookAnatomy() {
  const f = useFigureText('webhookAnatomy');
  const firstLine = 50;
  const step = 32;
  const boxRight = 404;
  const calloutX = 440;
  const calloutTop = 44;
  const calloutStep = 62;
  const notes = WIRE.map((line, index) => ({ ...line, y: firstLine + index * step })).filter((line) => line.note);

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 344">
      <rect x={8} y={firstLine - 26} width={boxRight - 8} height={WIRE.length * step + 12} rx={10} fill="none" stroke={CHROME.rail} />
      {WIRE.map((line, index) => (
        <text
          key={index}
          x={22}
          y={firstLine + index * step}
          fill={line.note ? CHROME.ink : SOFT}
          style={{ whiteSpace: 'pre' }}
          className={cn('font-mono text-[14px]', line.note && line.note !== 'body' && 'font-semibold')}
        >
          {line.text}
        </text>
      ))}
      {notes.map((line, index) => {
        const y = calloutTop + index * calloutStep;
        return (
          <g key={line.note}>
            <circle cx={boxRight - 6} cy={line.y - 5} r={2.5} fill={SERIES.brand} />
            <path
              d={`M ${boxRight - 6} ${line.y - 5} L ${boxRight + 10} ${line.y - 5} L ${calloutX - 14} ${y - 5} L ${calloutX - 6} ${y - 5}`}
              fill="none"
              stroke={SERIES.brand}
              strokeWidth={1}
            />
            <text x={calloutX} y={y} fill={CHROME.ink} className="text-[16px] font-semibold">
              {f(`${line.note}.title`)}
            </text>
            <text x={calloutX} y={y + 20} fill={SOFT} className="text-[14px]">
              {wrapWords(f(`${line.note}.body`), 36).map((part, row) => (
                <tspan key={row} x={calloutX} dy={row === 0 ? 0 : 18}>
                  {part}
                </tspan>
              ))}
            </text>
          </g>
        );
      })}
    </Figure>
  );
}

/** Answer inside the sender's budget, do the work where nobody is waiting. */
function ReceiverAck() {
  const f = useFigureText('receiverAck');
  const X0 = 176;
  const budgetEnd = 490;
  const lanes = { request: 70, worker: 150, inline: 236 };
  const steps = [
    { key: 'verify', x: X0, width: 66 },
    { key: 'record', x: X0 + 72, width: 66 },
    { key: 'enqueue', x: X0 + 144, width: 66 },
  ];

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 290">
      <defs>
        <Arrow id="wg-ack-arrow" />
        <Arrow id="wg-ack-arrow-ok" colour={SERIES.ok} />
      </defs>

      {/* The sender's timeout: everything left of the line is inside it. */}
      <rect x={X0} y={28} width={budgetEnd - X0} height={8} rx={4} fill={SERIES.idle} opacity={0.35} />
      {/* Drawn through the two lanes the sender is waiting on, and not through the worker's. */}
      <line x1={budgetEnd} y1={24} x2={budgetEnd} y2={lanes.request + 30} stroke={CHROME.ink} strokeWidth={1} strokeDasharray="4 3" />
      <line x1={budgetEnd} y1={lanes.inline - 22} x2={budgetEnd} y2={lanes.inline + 14} stroke={CHROME.ink} strokeWidth={1} strokeDasharray="4 3" />
      <text x={X0} y={20} fill={SOFT} className={MONO}>
        {f('budget')}
      </text>
      <text x={budgetEnd + 6} y={20} fill={CHROME.ink} className={cn(MONO, 'font-semibold')}>
        {f('timeout')}
      </text>

      {Object.entries(lanes).map(([key, y]) => (
        <g key={key}>
          <text x={12} y={y - 4} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
            {f(`lane.${key}.title`)}
          </text>
          <text x={12} y={y + 11} fill={SOFT} className={MONO}>
            {f(`lane.${key}.body`)}
          </text>
        </g>
      ))}

      {/* The request lane: three short steps and a 200, all well inside the budget. */}
      {steps.map((step) => (
        <g key={step.key}>
          <rect x={step.x} y={lanes.request - 12} width={step.width} height={20} rx={4} fill={SERIES.brand} opacity={0.85} />
          <text x={step.x + step.width / 2} y={lanes.request + 22} textAnchor="middle" fill={SOFT} className={MONO}>
            {f(`step.${step.key}`)}
          </text>
        </g>
      ))}
      <line x1={X0 + 216} y1={lanes.request - 2} x2={X0 + 244} y2={lanes.request - 2} stroke={SERIES.ok} strokeWidth={1.5} markerEnd="url(#wg-ack-arrow-ok)" />
      <text x={X0 + 250} y={lanes.request + 2} fill={SERIES.ok} className={cn(MONO, 'font-semibold')}>
        {f('ok')}
      </text>

      {/* The queue hands the event to a worker, which takes as long as the work takes. */}
      <path
        d={`M ${X0 + 177} ${lanes.request + 26} L ${X0 + 177} ${lanes.worker - 12}`}
        fill="none"
        stroke={CHROME.muted}
        markerEnd="url(#wg-ack-arrow)"
      />
      <rect x={X0 + 144} y={lanes.worker - 10} width={380} height={20} rx={4} fill={SERIES.brand} opacity={0.3} />
      <text x={X0 + 154} y={lanes.worker + 4} fill={CHROME.ink} className={MONO}>
        {f('work')}
      </text>
      <text x={708} y={lanes.worker + 26} textAnchor="end" fill={SOFT} className={MONO}>
        {f('workNote')}
      </text>

      {/* The same work done inline: it runs past the budget, and the sender gives up. */}
      <rect x={X0} y={lanes.inline - 10} width={budgetEnd - X0} height={20} rx={4} fill={SERIES.brand} opacity={0.3} />
      <rect x={budgetEnd} y={lanes.inline - 10} width={120} height={20} rx={4} fill={SERIES.halt} opacity={0.25} />
      <text x={X0 + 10} y={lanes.inline + 4} fill={CHROME.ink} className={MONO}>
        {f('inlineWork')}
      </text>
      <g transform={`translate(${budgetEnd}, ${lanes.inline})`}>
        <line x1={-5} y1={-5} x2={5} y2={5} stroke={SERIES.halt} strokeWidth={2} />
        <line x1={-5} y1={5} x2={5} y2={-5} stroke={SERIES.halt} strokeWidth={2} />
      </g>
      <text x={708} y={lanes.inline + 26} textAnchor="end" fill={SERIES.halt} className={cn(MONO, 'font-semibold')}>
        {f('inlineFail')}
      </text>
    </Figure>
  );
}

/**
 * The Standard Webhooks spec's example schedule, against a three-hour outage, on a log axis.
 * The five-hour attempt is the first to land after the endpoint is back.
 */
const BACKOFF = [
  { seconds: 0, label: '0' },
  { seconds: 5, label: '5s' },
  { seconds: 305, label: '5m' },
  { seconds: 2_105, label: '35m' },
  { seconds: 9_305, label: '2h35m' },
  { seconds: 27_305, label: '7h35m' },
];
const OUTAGE_SECONDS = 3 * 3_600;
const BACKOFF_SPAN = 86_400;

function RetryBackoff() {
  const f = useFigureText('retryBackoff');
  const X0 = 172;
  const X1 = 650;
  const x = (seconds: number) => X0 + (Math.log1p(seconds) / Math.log1p(BACKOFF_SPAN)) * (X1 - X0);
  const retryY = 104;
  const onceY = 184;
  const ticks = [
    { seconds: 0, key: 'now' },
    { seconds: 60, key: 'minute' },
    { seconds: 3_600, key: 'hour' },
    { seconds: 86_400, key: 'day' },
  ];

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 260">
      {/* The outage, across both lanes: a wash, because it is a period rather than an outcome. */}
      <rect x={x(0)} y={48} width={x(OUTAGE_SECONDS) - x(0)} height={160} fill={CHROME.muted} opacity={0.1} />
      <line x1={x(OUTAGE_SECONDS)} y1={48} x2={x(OUTAGE_SECONDS)} y2={208} stroke={CHROME.muted} strokeDasharray="3 3" />
      <text x={x(0) + 6} y={40} fill={CHROME.ink} className={cn(MONO, 'font-semibold')}>
        {f('outage')}
      </text>
      <text x={x(OUTAGE_SECONDS) + 6} y={40} fill={SOFT} className={MONO}>
        {f('back')}
      </text>

      <line x1={X0} y1={220} x2={X1} y2={220} {...AXIS} />
      {ticks.map((tick) => (
        <g key={tick.key}>
          <line x1={x(tick.seconds)} y1={220} x2={x(tick.seconds)} y2={226} {...AXIS} />
          <text x={x(tick.seconds)} y={240} textAnchor="middle" fill={SOFT} className={MONO}>
            {f(`tick.${tick.key}`)}
          </text>
        </g>
      ))}

      {[
        { key: 'retries', y: retryY },
        { key: 'once', y: onceY },
      ].map((lane) => (
        <g key={lane.key}>
          <line x1={X0} y1={lane.y} x2={X1} y2={lane.y} {...AXIS} strokeDasharray="2 4" />
          <text x={12} y={lane.y - 4} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
            {f(`${lane.key}.title`)}
          </text>
          <text x={12} y={lane.y + 11} fill={SOFT} className={MONO}>
            {f(`${lane.key}.body`)}
          </text>
        </g>
      ))}

      {/* With back-off: every attempt inside the outage is still owed; the first after it lands. */}
      {BACKOFF.map((attempt, index) => {
        const delivered = attempt.seconds > OUTAGE_SECONDS;
        return (
          <g key={attempt.seconds}>
            <circle cx={x(attempt.seconds)} cy={retryY} r={delivered ? 6 : 4.5} fill={delivered ? SERIES.ok : SERIES.retry} />
            <text
              x={x(attempt.seconds)}
              y={index % 2 === 0 ? retryY - 12 : retryY + 20}
              textAnchor="middle"
              fill={SOFT}
              className={MONO}
            >
              {attempt.label}
            </text>
          </g>
        );
      })}
      <text x={x(BACKOFF[BACKOFF.length - 1].seconds) + 10} y={retryY + 4} fill={SERIES.ok} className={cn(MONO, 'font-semibold')}>
        {f('retries.result')}
      </text>

      {/* One attempt, then nothing. */}
      <circle cx={x(0)} cy={onceY} r={5} fill={SERIES.halt} />
      <text x={x(0) + 12} y={onceY - 8} fill={SERIES.halt} className={cn(MONO, 'font-semibold')}>
        {f('once.result')}
      </text>
    </Figure>
  );
}

/** A retry after a lost response: the same id twice, and the second one skipped. */
function DuplicateDelivery() {
  const f = useFigureText('duplicateDelivery');
  const sender = 120;
  const receiver = 470;
  const rows = { first: 70, lostReply: 104, retry: 170, reply: 204 };

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 250">
      <defs>
        <Arrow id="wg-dup-arrow" colour={SERIES.brand} />
        <Arrow id="wg-dup-arrow-ok" colour={SERIES.ok} />
      </defs>

      {[
        { key: 'sender', x: sender },
        { key: 'receiver', x: receiver },
      ].map((party) => (
        <g key={party.key}>
          <text x={party.x} y={22} textAnchor="middle" fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
            {f(party.key)}
          </text>
          <line x1={party.x} y1={32} x2={party.x} y2={232} {...AXIS} />
        </g>
      ))}

      {/* First attempt: processed, but the reply never makes it back in time. */}
      <line x1={sender} y1={rows.first} x2={receiver - 4} y2={rows.first} stroke={SERIES.brand} strokeWidth={1.5} markerEnd="url(#wg-dup-arrow)" />
      <text x={(sender + receiver) / 2} y={rows.first - 8} textAnchor="middle" fill={CHROME.ink} className={MONO}>
        {f('post')}
      </text>
      <text x={receiver + 12} y={rows.first + 4} fill={CHROME.ink} className={MONO}>
        {f('processed')}
      </text>
      <line x1={receiver} y1={rows.lostReply} x2={sender + 150} y2={rows.lostReply} stroke={CHROME.muted} strokeWidth={1.5} strokeDasharray="4 4" />
      <g transform={`translate(${sender + 150}, ${rows.lostReply})`}>
        <line x1={-5} y1={-5} x2={5} y2={5} stroke={SERIES.halt} strokeWidth={2} />
        <line x1={-5} y1={5} x2={5} y2={-5} stroke={SERIES.halt} strokeWidth={2} />
      </g>
      <text x={sender + 150} y={rows.lostReply + 18} textAnchor="middle" fill={SERIES.halt} className={MONO}>
        {f('lost')}
      </text>

      {/* The retry: the same id, which the receiver has already recorded. */}
      <line x1={sender} y1={rows.retry} x2={receiver - 4} y2={rows.retry} stroke={SERIES.brand} strokeWidth={1.5} markerEnd="url(#wg-dup-arrow)" />
      <text x={(sender + receiver) / 2} y={rows.retry - 8} textAnchor="middle" fill={CHROME.ink} className={MONO}>
        {f('retry')}
      </text>
      <text x={receiver + 12} y={rows.retry + 4} fill={CHROME.ink} className={MONO}>
        {f('seen')}
      </text>
      <line x1={receiver} y1={rows.reply} x2={sender + 4} y2={rows.reply} stroke={SERIES.ok} strokeWidth={1.5} markerEnd="url(#wg-dup-arrow-ok)" />
      <text x={(sender + receiver) / 2} y={rows.reply - 8} textAnchor="middle" fill={SERIES.ok} className={cn(MONO, 'font-semibold')}>
        {f('ok')}
      </text>

      {/* Without the check, the second arrival does the work a second time. */}
      <text x={receiver + 12} y={rows.retry + 22} fill={SOFT} className={MONO}>
        {f('without')}
      </text>
    </Figure>
  );
}

/** Three events in the order they happened, and the order they arrived in. */
function OutOfOrder() {
  const f = useFigureText('outOfOrder');
  const left = 150;
  const right = 410;
  const rowY = (index: number) => 70 + index * 52;
  const happened = ['created', 'updated', 'cancelled'];
  /** Arrival order: `created` failed its first attempt and came back last, as a retry. */
  const arrived = ['updated', 'cancelled', 'created'];
  const verdict: Record<string, 'apply' | 'skip'> = { updated: 'apply', cancelled: 'apply', created: 'skip' };

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 240">
      <text x={left} y={30} textAnchor="end" fill={SOFT} className={cn(LABEL, 'font-semibold')}>
        {f('happened')}
      </text>
      <text x={right} y={30} fill={SOFT} className={cn(LABEL, 'font-semibold')}>
        {f('arrived')}
      </text>

      {happened.map((key, index) => {
        const to = arrived.indexOf(key);
        return (
          <g key={key}>
            <text x={left} y={rowY(index) + 4} textAnchor="end" fill={CHROME.ink} className={MONO}>
              {f(`event.${key}`)}
            </text>
            <line
              x1={left + 10}
              y1={rowY(index)}
              x2={right - 10}
              y2={rowY(to)}
              stroke={SERIES.brand}
              strokeWidth={1.5}
              opacity={0.75}
            />
            <circle cx={left + 10} cy={rowY(index)} r={3} fill={SERIES.brand} />
            <circle cx={right - 10} cy={rowY(to)} r={3} fill={SERIES.brand} />
          </g>
        );
      })}

      {arrived.map((key, index) => (
        <g key={key}>
          <text x={right} y={rowY(index) + 4} fill={CHROME.ink} className={MONO}>
            {f(`event.${key}`)}
          </text>
          <text
            x={708}
            y={rowY(index) + 4}
            textAnchor="end"
            fill={verdict[key] === 'apply' ? SERIES.ok : SOFT}
            className={cn(MONO, 'font-semibold')}
          >
            {f(`verdict.${key}`)}
          </text>
        </g>
      ))}
      <text x={708} y={226} textAnchor="end" fill={SOFT} className={MONO}>
        {f('rule')}
      </text>
    </Figure>
  );
}

/** Rotating a secret with an overlap, and without one. */
function SecretRotation() {
  const f = useFigureText('secretRotation');
  const X0 = 170;
  const X1 = 700;
  const rotate = 300;
  const retire = 590;
  const deploy = 440;
  const overlapY = 84;
  const cutY = 186;
  const barHeight = 18;

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 250">
      {[
        { key: 'overlap', y: overlapY },
        { key: 'cutover', y: cutY },
      ].map((lane) => (
        <g key={lane.key}>
          <text x={12} y={lane.y - 4} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
            {f(`${lane.key}.title`)}
          </text>
          <text x={12} y={lane.y + 11} fill={SOFT} className={MONO}>
            {f(`${lane.key}.body`)}
          </text>
        </g>
      ))}

      {/* Markers shared by both lanes: when the secret is rotated, and when the receiver deploys it. */}
      {[
        { key: 'rotate', x: rotate },
        { key: 'deploy', x: deploy },
      ].map((marker) => (
        <g key={marker.key}>
          {/* Broken between the lanes, so the overlap's result reads clear of it. */}
          <line x1={marker.x} y1={34} x2={marker.x} y2={overlapY + 22} stroke={CHROME.ink} strokeDasharray="3 3" />
          <line x1={marker.x} y1={cutY - 26} x2={marker.x} y2={226} stroke={CHROME.ink} strokeDasharray="3 3" />
          <text x={marker.x} y={26} textAnchor="middle" fill={CHROME.ink} className={cn(MONO, 'font-semibold')}>
            {f(`marker.${marker.key}`)}
          </text>
        </g>
      ))}

      {/* With an overlap: the old secret keeps signing until it retires, alongside the new one. */}
      <rect x={X0} y={overlapY - 22} width={retire - X0} height={barHeight} rx={4} fill={SERIES.brand} opacity={0.35} />
      <text x={X0 + 6} y={overlapY - 9} fill={CHROME.ink} className={MONO}>
        {f('old')}
      </text>
      <rect x={rotate} y={overlapY + 2} width={X1 - rotate} height={barHeight} rx={4} fill={SERIES.brand} opacity={0.85} />
      <text x={retire + 8} y={overlapY + 15} fill={CHROME.surface} className={cn(MONO, 'font-semibold')}>
        {f('new')}
      </text>
      <text x={(rotate + retire) / 2} y={overlapY + 36} textAnchor="middle" fill={SERIES.ok} className={cn(MONO, 'font-semibold')}>
        {f('overlap.result')}
      </text>
      <line x1={retire} y1={overlapY - 26} x2={retire} y2={overlapY + 22} stroke={CHROME.muted} strokeWidth={1} />
      <text x={retire + 4} y={overlapY - 30} fill={SOFT} className={MONO}>
        {f('marker.retire')}
      </text>

      {/* Without: the old secret stops the instant it is rotated, and every request until the
          deploy fails verification. */}
      <rect x={X0} y={cutY - 22} width={rotate - X0} height={barHeight} rx={4} fill={SERIES.brand} opacity={0.35} />
      <text x={X0 + 6} y={cutY - 9} fill={CHROME.ink} className={MONO}>
        {f('old')}
      </text>
      <rect x={rotate} y={cutY + 2} width={X1 - rotate} height={barHeight} rx={4} fill={SERIES.brand} opacity={0.85} />
      <rect x={rotate} y={cutY - 22} width={deploy - rotate} height={barHeight} rx={4} fill={SERIES.halt} opacity={0.3} />
      <text x={(rotate + deploy) / 2} y={cutY - 9} textAnchor="middle" fill={SERIES.halt} className={cn(MONO, 'font-semibold')}>
        {f('cutover.result')}
      </text>
      <text x={retire + 8} y={cutY + 15} fill={CHROME.surface} className={cn(MONO, 'font-semibold')}>
        {f('new')}
      </text>
    </Figure>
  );
}

/** Registered into `FIGURES` in `../figures.tsx`. */
export const WEBHOOKS_GUIDE_FIGURES: Record<string, () => JSX.Element> = {
  'polling-vs-push': PollingVsPush,
  'webhook-anatomy': WebhookAnatomy,
  'receiver-ack': ReceiverAck,
  'retry-backoff': RetryBackoff,
  'duplicate-delivery': DuplicateDelivery,
  'out-of-order': OutOfOrder,
  'secret-rotation': SecretRotation,
};
