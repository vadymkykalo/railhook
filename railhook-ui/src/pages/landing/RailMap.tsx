import { useEffect, useRef } from 'react';
import { Globe, Server, type LucideIcon } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RAILHOOK_MARK } from '../../components/icons/RailhookIcon';

/**
 * Webhooks coming in from three services on the left, going out to three places on the right,
 * with Railhook in the middle — and one delivery that fails, waits and arrives, so the reader sees
 * the promise in the headline happen rather than reading it.
 *
 * SMIL rather than CSS or JS: `animateMotion` moves a dot along the same path the rail is drawn
 * with, so the two cannot drift apart. Under prefers-reduced-motion the whole document timeline is
 * set to a frame where every dot is mid-rail and the retry dot is on its loop, then paused.
 *
 * Colours are tokens, so the drawing follows the theme. Retry and delivered are status hues — the
 * one place on the landing they appear, because the dots *are* statuses.
 */
const C = {
  rail: 'hsl(var(--input))',
  chip: 'hsl(var(--card))',
  ink: 'hsl(var(--foreground))',
  slate: 'hsl(var(--muted-foreground))',
  accent: 'hsl(var(--primary))',
  onAccent: 'hsl(var(--primary-foreground))',
  retry: 'hsl(var(--retry))',
  ok: 'hsl(var(--ok))',
};

const P = 'railmap-';

const PATHS = {
  in1: 'M190 70 C340 70 360 170 480 170',
  in2: 'M190 170 L480 170',
  in3: 'M190 270 C340 270 360 170 480 170',
  out1: 'M600 170 C720 170 740 70 890 70',
  out2: 'M600 170 L890 170',
  out3: 'M600 170 C680 170 700 270 760 270 L890 270',
  retry: 'M600 170 C680 170 700 270 760 270 A30 30 0 1 1 760 330 A30 30 0 1 1 760 270 L890 270',
} as const;

const FLIGHTS: { path: keyof typeof PATHS; dur: string; begin: string }[] = [
  { path: 'in1', dur: '2.6s', begin: '-0.4s' },
  { path: 'in2', dur: '2.2s', begin: '-1.5s' },
  { path: 'in3', dur: '2.8s', begin: '-2.1s' },
  { path: 'out1', dur: '2.6s', begin: '-1.1s' },
  { path: 'out2', dur: '2.3s', begin: '-0.2s' },
];

/**
 * A place events come from or go to. Third parties carry their own full-colour logo from
 * `/logos/brand` — the same files the architecture diagram and the directions scenes use — and
 * our own or generic places a neutral icon in the same slot, so every chip lines up.
 */
function Chip({ x, y, name, sub, logo, icon: Icon, mono = false }: {
  x: number;
  y: number;
  name: string;
  sub: string;
  logo?: string;
  icon?: LucideIcon;
  mono?: boolean;
}) {
  return (
    <g>
      <rect x={x} y={y} width="150" height="48" rx="10" fill={C.chip} stroke={C.rail} strokeWidth="1" />
      {logo && <image href={`/logos/brand/${logo}.svg`} x={x + 12} y={y + 13} width="22" height="22" className={mono ? 'dark:invert' : undefined} />}
      {Icon && <Icon x={x + 12} y={y + 13} width={22} height={22} color={C.slate} strokeWidth={1.75} />}
      <text x={x + 44} y={y + 20} fill={C.ink} fontSize="14" fontWeight="600" className="font-sans">
        {name}
      </text>
      <text x={x + 44} y={y + 38} fill={C.slate} fontSize="11" className="font-mono">
        {sub}
      </text>
    </g>
  );
}

export default function RailMap() {
  const { t } = useTranslation();
  const svgRef = useRef<SVGSVGElement | null>(null);

  useEffect(() => {
    const svg = svgRef.current;
    if (!svg || typeof window.matchMedia !== 'function') return;
    if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    if (typeof svg.setCurrentTime === 'function' && typeof svg.pauseAnimations === 'function') {
      svg.setCurrentTime(2.5);
      svg.pauseAnimations();
    }
  }, []);

  const retryMotion = <animateMotion dur="6s" repeatCount="indefinite" begin="-1.8s" calcMode="linear"><mpath href={`#${P}retry`} /></animateMotion>;

  return (
    <figure className="m-0">
      <div className="overflow-x-auto">
        <svg
          ref={svgRef}
          viewBox="0 0 1080 360"
          role="img"
          aria-label={t('landing.map.aria')}
          className="block h-auto w-full min-w-[720px]"
        >
          <defs>
            {Object.entries(PATHS).map(([key, d]) => (
              <path key={key} id={`${P}${key}`} d={d} />
            ))}
          </defs>

          <text x="40" y="22" fill={C.slate} fontSize="11" fontWeight="500" letterSpacing="1.3" className="font-mono">
            {t('landing.map.receive')}
          </text>
          <text x="1040" y="22" textAnchor="end" fill={C.slate} fontSize="11" fontWeight="500" letterSpacing="1.3" className="font-mono">
            {t('landing.map.send')}
          </text>

          {(['in1', 'in2', 'in3', 'out1', 'out2', 'out3'] as const).map((key) => (
            <use key={key} href={`#${P}${key}`} fill="none" stroke={C.rail} strokeWidth="2" />
          ))}
          <circle cx="760" cy="300" r="30" fill="none" stroke={C.retry} strokeWidth="1.5" strokeDasharray="2 4" opacity="0.8" />
          <text x="760" y="352" textAnchor="middle" fill={C.retry} fontSize="11" fontWeight="600" className="font-mono">
            {t('landing.map.retryLabel')}
          </text>

          <Chip x={40} y={46} name="Stripe" sub="invoice.paid" logo="stripe" />
          <Chip x={40} y={146} name="GitHub" sub="push" logo="github" mono />
          <Chip x={40} y={246} name="Shopify" sub="orders/create" logo="shopify" />

          <rect x="490" y="120" width="100" height="100" rx="24" fill={C.accent} />
          <g transform="translate(510 140) scale(3)" fill="none" stroke={C.onAccent} strokeWidth="2.25" strokeLinecap="round" strokeLinejoin="round">
            <path d={RAILHOOK_MARK.hook} />
            <path d={RAILHOOK_MARK.flow} />
            <circle {...RAILHOOK_MARK.origin} fill={C.onAccent} stroke="none" />
          </g>
          <text x="540" y="244" textAnchor="middle" fill={C.ink} fontSize="13" fontWeight="600" className="font-sans">
            Railhook
          </text>

          <Chip x={890} y={46} name={t('landing.map.yourService')} sub="api.acme.com" icon={Server} />
          <Chip x={890} y={146} name="Slack" sub="hooks.slack.com" logo="slack" />
          <Chip x={890} y={246} name={t('landing.map.customer')} sub="northwind.io" icon={Globe} />

          {FLIGHTS.map((f) => (
            <circle key={f.path} r="5" fill={C.accent}>
              <animateMotion dur={f.dur} repeatCount="indefinite" begin={f.begin}>
                <mpath href={`#${P}${f.path}`} />
              </animateMotion>
            </circle>
          ))}

          {/* The retry: out on its way, fails and waits round the loop, then arrives. */}
          <circle r="5.5" fill={C.accent}>
            {retryMotion}
            <animate attributeName="opacity" dur="6s" repeatCount="indefinite" begin="-1.8s" values="1;1;0;0;0" keyTimes="0;0.37;0.39;0.97;1" />
          </circle>
          <circle r="5.5" fill={C.retry}>
            {retryMotion}
            <animate attributeName="opacity" dur="6s" repeatCount="indefinite" begin="-1.8s" values="0;0;1;1;0;0" keyTimes="0;0.37;0.39;0.74;0.76;1" />
          </circle>
          <circle r="5.5" fill={C.ok}>
            {retryMotion}
            <animate attributeName="opacity" dur="6s" repeatCount="indefinite" begin="-1.8s" values="0;0;1;1" keyTimes="0;0.74;0.76;1" />
          </circle>
        </svg>
      </div>
      <figcaption className="mt-2 flex flex-wrap justify-center gap-x-5 gap-y-1 font-mono text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-2">
          <i aria-hidden="true" className="h-2 w-2 rounded-full bg-primary" />
          {t('landing.map.legendFlight')}
        </span>
        <span className="inline-flex items-center gap-2">
          <i aria-hidden="true" className="h-2 w-2 rounded-full bg-retry" />
          {t('landing.map.legendRetry')}
        </span>
        <span className="inline-flex items-center gap-2">
          <i aria-hidden="true" className="h-2 w-2 rounded-full bg-ok" />
          {t('landing.map.legendOk')}
        </span>
      </figcaption>
    </figure>
  );
}
