import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Check, Minus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import { panel, LogoMark, Reveal, Section, SectionHeader } from './primitives';
import { cn } from '../../lib/utils';
import { CONTACT_PATH, PLANS, REPO_URL, SELF_HOSTED_RATE, type LandingPlan } from './plans';

/**
 * The fork: run it yourself, or let us run it — and then, which plan.
 *
 * Every card here ends in something the reader can click. The version this
 * replaces rendered the four plans as a reference table with no buttons at all,
 * so a reader who had scrolled past six sections and picked a plan — the
 * warmest signal on the page — was handed a dead end, while the only CTA in the
 * grid sat on Enterprise and opened a mail client.
 *
 * The numbers come from `plans.ts`, which is the seeded V036/V038/V042 rows.
 * Nothing here is a marketing figure, and nothing is a promise the product does
 * not keep: the feature rows are the three entitlement flags that actually gate
 * behaviour. SSO used to be listed on Enterprise and there is no SSO in the
 * tree — that row is gone rather than footnoted.
 */

const SELF_HOSTED_ROWS = ['events', 'projects', 'endpoints', 'members', 'retention'] as const;

const RUNTIME = [
  { name: 'PostgreSQL', src: '/logos/postgresql.svg' },
  { name: 'Apache Kafka', src: '/logos/apachekafka.svg' },
  { name: 'Redis', src: '/logos/redis.svg' },
  { name: 'Docker', src: '/logos/docker.svg' },
  { name: 'Spring Boot', src: '/logos/springboot.svg' },
];

const FEATURE_ROWS = [
  { key: 'rowWorkflows', flag: 'workflows' },
  { key: 'rowMtls', flag: 'mtls' },
  { key: 'rowSupport', flag: 'support' },
] as const;

/** Starter is the plan the funnel is shaped around; Free is the way in. */
const POPULAR: LandingPlan['key'] = 'starter';

type Billing = 'monthly' | 'yearly';

function PlanCard({ plan, billing }: { plan: LandingPlan; billing: Billing }) {
  const { t, i18n } = useTranslation();
  const nf = new Intl.NumberFormat(i18n.language);
  const amount = (value: number | null) => (value === null ? t('landing.pricing.unlimited') : nf.format(value));

  const isEnterprise = plan.monthly === null;
  const isFree = plan.monthly === 0;
  const popular = plan.key === POPULAR;

  /* A yearly price is shown as its monthly equivalent so the two billing modes
     are comparable at a glance; the full annual figure stays underneath. */
  const price = isEnterprise
    ? t('landing.pricing.custom')
    : billing === 'yearly' && plan.yearly !== null
      ? `$${Math.round(plan.yearly / 12)}${t('landing.pricing.perMonth')}`
      : `$${plan.monthly}${t('landing.pricing.perMonth')}`;

  const specs = [
    ['events', amount(plan.events)],
    ['projects', amount(plan.projects)],
    ['endpoints', amount(plan.endpoints)],
    ['members', amount(plan.members)],
    ['rate', nf.format(plan.rate)],
    ['retention', nf.format(plan.retention)],
    [
      'tunnels',
      plan.tunnels === null
        ? t('landing.pricing.unlimited')
        : plan.tunnels === 0
          ? t('landing.pricing.tunnelsNone')
          : nf.format(plan.tunnels),
    ],
  ] as const;

  return (
    <div
      className={cn(
        'flex h-full flex-col p-5',
        panel(true),
        popular && 'border-primary/60 shadow-elevated',
      )}
    >
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="text-[15px] font-semibold text-foreground">{t(`landing.pricing.${plan.key}`)}</h3>
        {popular && (
          <span className="rounded-full bg-primary/10 px-2 py-0.5 font-mono text-[10px] uppercase tracking-wide text-primary">
            {t('landing.pricing.popular')}
          </span>
        )}
      </div>

      <p className="mt-3 font-display text-2xl tracking-tight text-foreground">{price}</p>
      {billing === 'yearly' && plan.yearly !== null && (
        <p className="mt-1 font-mono text-[11px] text-muted-foreground">
          {t('landing.pricing.yearlyNote', { price: `$${plan.yearly}` })}
        </p>
      )}

      <dl className="mt-4 space-y-1.5 border-t border-rail pt-3 font-mono text-[11.5px]">
        {specs.map(([row, value]) => (
          <div key={row} className="flex justify-between gap-3">
            <dt className="text-muted-foreground">{t(`landing.pricing.${row}`)}</dt>
            <dd className="text-foreground">{value}</dd>
          </div>
        ))}
      </dl>

      <ul className="mt-3 space-y-1.5 border-t border-rail pt-3 text-[12.5px]">
        {FEATURE_ROWS.map(({ key, flag }) => {
          const on = plan[flag];
          return (
            <li key={key} className={cn('flex items-start gap-2', on ? 'text-foreground' : 'text-muted-foreground')}>
              {on ? (
                <Check className="mt-0.5 h-3 w-3 shrink-0 text-ok" aria-hidden="true" />
              ) : (
                <Minus className="mt-0.5 h-3 w-3 shrink-0" aria-hidden="true" />
              )}
              {t(`landing.pricing.${key}`)}
            </li>
          );
        })}
      </ul>

      <div className="mt-auto pt-5">
        {isEnterprise ? (
          <Link to={CONTACT_PATH}>
            <Button variant="outline" className="w-full whitespace-normal">
              {t('landing.pricing.ctaEnterprise')}
            </Button>
          </Link>
        ) : (
          <Link to="/register">
            <Button variant={popular ? 'default' : 'outline'} className="w-full whitespace-normal">
              {isFree ? t('landing.pricing.ctaFree') : t('landing.pricing.ctaPaid')}
            </Button>
          </Link>
        )}
      </div>
    </div>
  );
}

export default function PricingSection() {
  const { t, i18n } = useTranslation();
  const nf = new Intl.NumberFormat(i18n.language);
  const [billing, setBilling] = useState<Billing>('monthly');
  const free = PLANS[0];

  const amount = (value: number | null) => (value === null ? t('landing.pricing.unlimited') : nf.format(value));

  return (
    <Section id="pricing">
      <Reveal>
        <SectionHeader
          eyebrow={t('landing.pricing.eyebrow')}
          title={t('landing.pricing.title')}
          body={t('landing.pricing.body')}
        />
      </Reveal>

      <div className="mt-10 grid gap-5 md:grid-cols-2">
        <div className={cn('flex flex-col p-6 sm:p-7', panel(true))}>
          <p className="mono-label">{t('landing.pricing.selfHostedLabel')}</p>
          <h3 className="mt-3 text-title text-foreground">{t('landing.pricing.selfHostedName')}</h3>
          <p className="mt-3 flex items-baseline gap-2">
            <span className="font-display text-3xl tracking-tight text-foreground">{t('landing.pricing.selfHostedPrice')}</span>
            <span className="font-mono text-[11.5px] text-muted-foreground">{t('landing.pricing.selfHostedPriceNote')}</span>
          </p>
          <p className="mt-4 text-[15px] leading-relaxed text-muted-foreground">{t('landing.pricing.selfHostedBody')}</p>
          <dl className="mt-5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5 border-t border-rail pt-4 font-mono text-[11.5px]">
            {SELF_HOSTED_ROWS.map((row) => (
              <div key={row} className="contents">
                <dt className="text-muted-foreground">{t(`landing.pricing.${row}`)}</dt>
                <dd className="text-foreground">{t('landing.pricing.unlimited')}</dd>
              </div>
            ))}
            <dt className="text-muted-foreground">{t('landing.pricing.rate')}</dt>
            <dd className="text-foreground">{nf.format(SELF_HOSTED_RATE)}</dd>
          </dl>

          {/* The stack used to lead the page, one screen under the hero, where
              it argued for self-hosting to a reader who had not yet been told
              self-hosting was on offer. It belongs to this card. */}
          <div className="mt-5 border-t border-rail pt-4">
            <p className="mono-label">{t('landing.pricing.runsOnLabel')}</p>
            <ul className="mt-3 flex flex-wrap items-center gap-3">
              {RUNTIME.map((mark) => (
                <li key={mark.name} title={mark.name}>
                  <LogoMark src={mark.src} name={mark.name} className="h-5 w-5" />
                </li>
              ))}
            </ul>
            <p className="mt-3 text-[13px] leading-relaxed text-muted-foreground">{t('landing.pricing.runsOnNote')}</p>
          </div>

          <div className="mt-auto flex flex-wrap gap-2.5 pt-6">
            <a href={REPO_URL} target="_blank" rel="noopener noreferrer">
              <Button variant="outline">{t('landing.pricing.selfHostedCta')}</Button>
            </a>
            <Link to="/docs">
              <Button variant="ghost">{t('landing.pricing.selfHostedCtaSecondary')}</Button>
            </Link>
          </div>
        </div>

        <div className={cn('flex flex-col p-6 sm:p-7', panel(true))}>
          <p className="mono-label">{t('landing.pricing.cloudLabel')}</p>
          <h3 className="mt-3 text-title text-foreground">{t('landing.pricing.cloudName')}</h3>
          <p className="mt-3 flex items-baseline gap-2">
            <span className="font-display text-3xl tracking-tight text-foreground">$0</span>
            <span className="font-mono text-[11.5px] text-muted-foreground">{t('landing.pricing.free')}</span>
          </p>
          <p className="mt-4 text-[15px] leading-relaxed text-muted-foreground">{t('landing.pricing.cloudBody')}</p>
          <dl className="mt-5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5 border-t border-rail pt-4 font-mono text-[11.5px]">
            <dt className="text-muted-foreground">{t('landing.pricing.events')}</dt>
            <dd className="text-foreground">{amount(free.events)}</dd>
            <dt className="text-muted-foreground">{t('landing.pricing.projects')}</dt>
            <dd className="text-foreground">{amount(free.projects)}</dd>
            <dt className="text-muted-foreground">{t('landing.pricing.endpoints')}</dt>
            <dd className="text-foreground">{amount(free.endpoints)}</dd>
            <dt className="text-muted-foreground">{t('landing.pricing.members')}</dt>
            <dd className="text-foreground">{amount(free.members)}</dd>
            <dt className="text-muted-foreground">{t('landing.pricing.rate')}</dt>
            <dd className="text-foreground">{nf.format(free.rate)}</dd>
          </dl>
          <div className="mt-auto flex flex-wrap gap-2.5 pt-6">
            <Link to="/register">
              <Button>
                {t('landing.pricing.cloudCta')} <ArrowRight className="h-4 w-4" aria-hidden="true" />
              </Button>
            </Link>
            <Link to={CONTACT_PATH}>
              <Button variant="outline">{t('landing.pricing.contactSales')}</Button>
            </Link>
          </div>
        </div>
      </div>

      <div className="mt-12 flex justify-center">
        <div
          role="group"
          aria-label={t('landing.pricing.billingAria')}
          className="inline-flex items-center gap-1 rounded-lg border border-rail bg-card p-1"
        >
          {(['monthly', 'yearly'] as const).map((mode) => (
            <button
              key={mode}
              type="button"
              onClick={() => setBilling(mode)}
              aria-pressed={billing === mode}
              className={cn(
                'rounded-md px-3 py-1.5 text-[13px] transition-colors',
                billing === mode
                  ? 'bg-secondary text-foreground'
                  : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {t(`landing.pricing.${mode}`)}
              {mode === 'yearly' && (
                <span className="ml-2 font-mono text-[10px] text-primary">{t('landing.pricing.yearlySave')}</span>
              )}
            </button>
          ))}
        </div>
      </div>

      <div id="plans" className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {PLANS.map((plan, i) => (
          <Reveal key={plan.key} delay={i * 60} className="h-full">
            <PlanCard plan={plan} billing={billing} />
          </Reveal>
        ))}
      </div>

      <p className="mt-6 max-w-3xl text-sm text-muted-foreground">{t('landing.pricing.upgradeNote')}</p>
      <p className="mt-2 max-w-3xl text-sm text-muted-foreground">{t('landing.pricing.featuresFrom')}</p>
    </Section>
  );
}
