import { useTranslation } from 'react-i18next';
import { CHROME, SERIES } from '../charts/chartTheme';
import { cn } from '../../lib/utils';
import { WEBHOOKS_GUIDE_FIGURES } from './figures/webhooks-guide';

/**
 * The drawings a post can place with `:::figure <key>`.
 *
 * Hand-written SVG rather than a chart library: these are arguments, not data — a timeline that
 * shows Shopify giving up four hours in while Stripe is still trying two days later says the
 * thing the paragraph above it is claiming. Every colour is a token
 * (`components/charts/chartTheme.ts`), so one drawing works on paper and on ink, and the status
 * hues keep their meanings: `ok` delivered, `retry` still owed, `halt` abandoned.
 *
 * Each figure is one `<figure>` with a `role="img"` and a written-out label, because a reader on
 * a screen reader is owed the same point the picture makes, and a caption under it. Every word
 * inside is a translation key: these pages are published in both languages.
 *
 * To add one: write the component, register it in `FIGURES`, and name the key in a post.
 */

export const AXIS = { stroke: CHROME.rail, strokeWidth: 1 };
export const LABEL = 'text-[11px]';
export const MONO = 'font-mono text-[10px]';

export function Figure({
  label,
  caption,
  viewBox,
  className,
  children,
}: {
  label: string;
  caption: string;
  viewBox: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <figure className="my-9">
      <div className="overflow-x-auto rounded-xl border border-rail bg-card p-4 sm:p-6">
        <svg
          role="img"
          aria-label={label}
          viewBox={viewBox}
          preserveAspectRatio="xMidYMid meet"
          className={cn('h-auto w-full min-w-[520px]', className)}
        >
          {children}
        </svg>
      </div>
      <figcaption className="mt-3 text-[13px] leading-relaxed text-muted-foreground">{caption}</figcaption>
    </figure>
  );
}

/** Seconds onto the 0…3-day axis, log-scaled: the first minute matters as much as the last day. */
const SPAN_SECONDS = 259_200;
const X0 = 168;
const X1 = 702;

function timeX(seconds: number): number {
  const fraction = Math.log1p(Math.max(seconds, 0)) / Math.log1p(SPAN_SECONDS);
  return X0 + fraction * (X1 - X0);
}

const TICKS: { seconds: number; key: string }[] = [
  { seconds: 0, key: 'now' },
  { seconds: 60, key: 'oneMinute' },
  { seconds: 900, key: 'fifteenMinutes' },
  { seconds: 14_400, key: 'fourHours' },
  { seconds: 86_400, key: 'oneDay' },
  { seconds: 259_200, key: 'threeDays' },
];

/**
 * How long each provider keeps trying, on one axis.
 *
 * Stripe is drawn as a band rather than as dots: it publishes "exponential back off" and a
 * three-day window, not the intervals, and inventing six plausible dots would be inventing facts.
 */
function ProviderRetries() {
  const { t } = useTranslation();
  const f = (key: string) => t(`blog.figures.providerRetries.${key}`);
  const lanes = [
    { key: 'stripe', y: 64, logo: 'stripe' },
    { key: 'github', y: 132, logo: 'github' },
    { key: 'shopify', y: 200, logo: 'shopify' },
  ];
  /** Shopify: up to eight calls inside four hours. Spaced on the same log axis. */
  const shopify = [0, 120, 480, 1_200, 2_700, 5_400, 9_000, 14_400];

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 280">
      {/* The axis, and one hairline per lane so a reader can follow a row across. */}
      {lanes.map((lane) => (
        <line key={lane.key} x1={X0} y1={lane.y} x2={X1} y2={lane.y} {...AXIS} strokeDasharray="2 4" />
      ))}
      <line x1={X0} y1={244} x2={X1} y2={244} {...AXIS} />
      {TICKS.map((tick) => (
        <g key={tick.key}>
          <line x1={timeX(tick.seconds)} y1={244} x2={timeX(tick.seconds)} y2={250} {...AXIS} />
          <text
            x={timeX(tick.seconds)}
            y={264}
            textAnchor="middle"
            fill={CHROME.muted}
            className={MONO}
          >
            {f(`tick.${tick.key}`)}
          </text>
        </g>
      ))}

      {lanes.map((lane) => (
        <g key={lane.key}>
          <image href={`/logos/brand/${lane.logo}.svg`} x={12} y={lane.y - 18} width={16} height={16} />
          <text x={36} y={lane.y - 5} fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
            {f(`${lane.key}.name`)}
          </text>
          <text x={36} y={lane.y + 10} fill={CHROME.muted} className={MONO}>
            {f(`${lane.key}.timeout`)}
          </text>
        </g>
      ))}

      {/* Stripe: a band from the first attempt to the end of the three-day window. */}
      <rect
        x={timeX(0)}
        y={lanes[0].y - 8}
        width={timeX(SPAN_SECONDS) - timeX(0)}
        height={16}
        rx={8}
        fill={SERIES.retry}
        opacity={0.18}
      />
      <rect x={timeX(0) - 1} y={lanes[0].y - 8} width={3} height={16} fill={SERIES.retry} />
      <text x={timeX(60)} y={lanes[0].y + 4} fill={CHROME.ink} className={LABEL}>
        {f('stripe.body')}
      </text>
      <text x={X1} y={lanes[0].y - 16} textAnchor="end" fill={CHROME.muted} className={MONO}>
        {f('stripe.end')}
      </text>

      {/* GitHub: one attempt, then nothing but a three-day window you can redeliver inside. */}
      <circle cx={timeX(0)} cy={lanes[1].y} r={5} fill={SERIES.halt} />
      <line
        x1={timeX(0)}
        y1={lanes[1].y}
        x2={timeX(SPAN_SECONDS)}
        y2={lanes[1].y}
        stroke={CHROME.muted}
        strokeWidth={1}
        strokeDasharray="5 5"
      />
      <text x={timeX(0) + 14} y={lanes[1].y - 8} fill={CHROME.ink} className={LABEL}>
        {f('github.body')}
      </text>
      <text x={X1} y={lanes[1].y - 16} textAnchor="end" fill={CHROME.muted} className={MONO}>
        {f('github.end')}
      </text>

      {/* Shopify: eight calls inside four hours, then the subscription is gone. */}
      {shopify.map((seconds, index) => (
        <circle key={seconds} cx={timeX(seconds)} cy={lanes[2].y} r={4} fill={SERIES.retry} opacity={index === 0 ? 1 : 0.85} />
      ))}
      <line
        x1={timeX(0)}
        y1={lanes[2].y}
        x2={timeX(14_400)}
        y2={lanes[2].y}
        stroke={SERIES.retry}
        strokeWidth={2}
      />
      <g transform={`translate(${timeX(86_400)}, ${lanes[2].y})`}>
        <line x1={-5} y1={-5} x2={5} y2={5} stroke={SERIES.halt} strokeWidth={2} />
        <line x1={-5} y1={5} x2={5} y2={-5} stroke={SERIES.halt} strokeWidth={2} />
      </g>
      {/* Right-anchored like the other two lanes' outcomes, so the longest label cannot run off
          the drawing. */}
      <text x={X1} y={lanes[2].y - 16} textAnchor="end" fill={SERIES.halt} className={cn(MONO, 'font-semibold')}>
        {f('shopify.end')}
      </text>
      <text x={timeX(0) + 6} y={lanes[2].y - 12} fill={CHROME.ink} className={LABEL}>
        {f('shopify.body')}
      </text>
    </Figure>
  );
}

/** Railhook's own outgoing ladder, as the wait before each attempt. */
const LADDER_SECONDS = [60, 300, 900, 3_600, 21_600, 86_400];

function RetryLadder() {
  const { t } = useTranslation();
  const f = (key: string) => t(`blog.figures.retryLadder.${key}`);
  const baseline = 190;
  const top = 34;
  const scale = (seconds: number) => (Math.log1p(seconds) / Math.log1p(86_400)) * (baseline - top);

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 230">
      <line x1={56} y1={baseline} x2={690} y2={baseline} {...AXIS} />
      {LADDER_SECONDS.map((seconds, index) => {
        const width = 74;
        const gap = 26;
        const x = 70 + index * (width + gap);
        const height = scale(seconds);
        return (
          <g key={seconds}>
            <rect x={x} y={baseline - height} width={width} height={height} rx={4} fill={SERIES.brand} opacity={0.85} />
            <text x={x + width / 2} y={baseline - height - 8} textAnchor="middle" fill={CHROME.ink} className={cn(MONO, 'font-semibold')}>
              {f(`wait.${index}`)}
            </text>
            <text x={x + width / 2} y={baseline + 16} textAnchor="middle" fill={CHROME.muted} className={MONO}>
              {t('blog.figures.retryLadder.attempt', { number: index + 2 })}
            </text>
          </g>
        );
      })}
      <text x={56} y={20} fill={CHROME.muted} className={LABEL}>
        {f('axis')}
      </text>
      <text x={690} y={baseline + 34} textAnchor="end" fill={CHROME.muted} className={LABEL}>
        {f('total')}
      </text>
    </Figure>
  );
}

/** Where a gateway sits, and what it does that the provider will not. */
function GatewayPipeline() {
  const { t } = useTranslation();
  const f = (key: string) => t(`blog.figures.gateway.${key}`);
  const boxes = [
    { key: 'provider', x: 8, width: 190, logos: ['stripe', 'github', 'shopify'] },
    { key: 'gateway', x: 252, width: 216, logos: [] as string[] },
    { key: 'app', x: 522, width: 190, logos: [] as string[] },
  ];
  const y = 40;
  const height = 124;
  const middle = y + height / 2;

  return (
    <Figure label={f('aria')} caption={f('caption')} viewBox="0 0 720 230">
      <defs>
        <marker id="blog-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
          <path d="M0,0 L8,4 L0,8 z" fill={CHROME.muted} />
        </marker>
      </defs>

      {boxes.map((box) => (
        <g key={box.key}>
          <rect
            x={box.x}
            y={y}
            width={box.width}
            height={height}
            rx={10}
            fill="none"
            stroke={box.key === 'gateway' ? SERIES.brand : CHROME.rail}
            strokeWidth={box.key === 'gateway' ? 1.5 : 1}
          />
          <text x={box.x + box.width / 2} y={y + 28} textAnchor="middle" fill={CHROME.ink} className={cn(LABEL, 'font-semibold')}>
            {f(`${box.key}.title`)}
          </text>
          <text x={box.x + box.width / 2} y={y + 48} textAnchor="middle" fill={CHROME.muted} className={MONO}>
            {f(`${box.key}.body`)}
          </text>
          {box.logos.map((logo, index) => (
            <image
              key={logo}
              href={`/logos/brand/${logo}.svg`}
              x={box.x + box.width / 2 - 32 + index * 24}
              y={y + 62}
              width={16}
              height={16}
            />
          ))}
        </g>
      ))}

      {/* What the gateway does, inside its own box: the three checks on the way in, then the one
          thing the stored copy makes possible afterwards. */}
      {['verify', 'dedup', 'store'].map((step, index) => (
        <text key={step} x={boxes[1].x + 18} y={y + 72 + index * 15} fill={SERIES.brand} className={MONO}>
          {f(`step.${step}`)}
        </text>
      ))}
      <text x={boxes[1].x + 18} y={y + 72 + 3 * 15} fill={CHROME.muted} className={MONO}>
        {f('replay')}
      </text>

      <line x1={204} y1={middle} x2={246} y2={middle} stroke={CHROME.muted} markerEnd="url(#blog-arrow)" />
      <line x1={474} y1={middle} x2={516} y2={middle} stroke={CHROME.muted} markerEnd="url(#blog-arrow)" />

      {/* The retry loop: the gateway owns it, so a provider that has given up no longer matters. */}
      <path
        d={`M ${617} ${y + height} L ${617} ${y + height + 32} L ${360} ${y + height + 32} L ${360} ${y + height}`}
        fill="none"
        stroke={SERIES.retry}
        strokeWidth={1.5}
        markerEnd="url(#blog-arrow)"
      />
      <text x={489} y={y + height + 48} textAnchor="middle" fill={SERIES.retry} className={cn(MONO, 'font-semibold')}>
        {f('loop')}
      </text>
    </Figure>
  );
}

/** Every figure a post may place, by the key it is placed with. */
export const FIGURES: Record<string, () => JSX.Element> = {
  'provider-retries': ProviderRetries,
  'retry-ladder': RetryLadder,
  gateway: GatewayPipeline,
  ...WEBHOOKS_GUIDE_FIGURES,
};
