import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button } from '../components/ui/button';
import TryDemoButton from '../components/site/TryDemoButton';
import { useAuth } from '../auth/auth.store';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { useJsonLd } from '../hooks/useJsonLd';
import { cn } from '../lib/utils';
import { Band, SectionHeading, WRAP } from './landing/primitives';
import { FREE_PLAN } from './landing/plans';

/**
 * What Railhook costs: nothing, in the cloud or on your own servers.
 *
 * There is one plan and it is free, so this is not a plan grid: the free cloud allowance with
 * every figure a reader checks before signing up, the self-hosted promise, and the questions
 * people ask first — also published as FAQPage data. No other vendor's prices: the owner's call,
 * a price comparison reads as a discount shop. Feature comparisons live in the docs.
 */

/** FAQ order is part of the page, and the FAQPage data follows it. */
const FAQ = ['free', 'limit', 'card', 'paid', 'selfHost'] as const;

const CARD = 'flex flex-col gap-3 rounded-2xl border border-rail bg-card p-5 sm:p-7';
const PILL = 'self-start rounded-full bg-accent px-2.5 py-0.5 font-mono text-[11px] uppercase tracking-[0.08em] text-primary';

export default function PricingPage() {
  const { t, i18n } = useTranslation();
  const { isAuthenticated } = useAuth();
  useDocumentMeta({ titleKey: 'meta.pricing.title', descriptionKey: 'meta.pricing.description', path: '/pricing' });

  const events = new Intl.NumberFormat(i18n.language).format(FREE_PLAN.events);

  useJsonLd('pricing', {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: FAQ.map((id) => ({
      '@type': 'Question',
      name: t(`pricing.faq.${id}.q`),
      acceptedAnswer: { '@type': 'Answer', text: t(`pricing.faq.${id}.a`) },
    })),
  });

  return (
    <>
      <section className="pb-4 pt-14 sm:pt-20">
        <div className={WRAP}>
          <h1 className="max-w-3xl font-display text-[2.2rem] font-bold leading-[1.05] tracking-[-0.035em] text-foreground [text-wrap:balance] sm:text-[3.2rem]">
            {t('pricing.title')}
          </h1>
          <p className="mt-4 max-w-2xl text-[1.1rem] text-muted-foreground">{t('pricing.lead')}</p>
        </div>
      </section>

      <Band labelledBy="pricing-plans">
        <h2 id="pricing-plans" className="sr-only">{t('pricing.title')}</h2>
        <div className="grid gap-5 md:grid-cols-2">
          <article className={cn(CARD, 'border-primary shadow-elevated ring-1 ring-primary')}>
            <span className="self-start rounded-full bg-primary px-2.5 py-0.5 font-mono text-[11px] uppercase tracking-[0.08em] text-primary-foreground">
              {t('pricing.cloud.pill')}
            </span>
            <h3 className="text-[1.35rem] font-semibold tracking-[-0.01em] text-foreground">{t('pricing.cloud.title')}</h3>
            <p className="font-display text-[2rem] font-bold text-foreground">{t('pricing.cloud.price')}</p>
            <p className="text-muted-foreground">
              {t('pricing.cloud.body', {
                events,
                projects: FREE_PLAN.projects,
                endpoints: FREE_PLAN.endpointsPerProject,
                retention: FREE_PLAN.retention,
              })}
            </p>
            <p className="text-sm text-muted-foreground">{t('pricing.cloud.later')}</p>
            <div className="mt-auto flex flex-wrap gap-2.5 pt-3">
              <Button asChild className="max-sm:w-full">
                {isAuthenticated ? (
                  <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
                ) : (
                  <Link to="/register">{t('pricing.cloud.cta')}</Link>
                )}
              </Button>
              <TryDemoButton variant="outline" className="max-sm:w-full" />
            </div>
          </article>

          <article className={CARD}>
            <span className={PILL}>{t('pricing.selfHosted.pill')}</span>
            <h3 className="text-[1.35rem] font-semibold tracking-[-0.01em] text-foreground">{t('pricing.selfHosted.title')}</h3>
            <p className="font-display text-[2rem] font-bold text-foreground">{t('pricing.selfHosted.price')}</p>
            <p className="text-muted-foreground">{t('pricing.selfHosted.body')}</p>
            <div className="mt-auto pt-3">
              <Button asChild variant="outline" className="max-sm:w-full">
                <a href="/docs/self-hosting/overview/">{t('pricing.selfHosted.cta')}</a>
              </Button>
            </div>
          </article>
        </div>
      </Band>

      <Band labelledBy="pricing-faq">
        <SectionHeading id="pricing-faq" title={t('pricing.faq.title')} />
        <dl className="grid gap-x-10 gap-y-8 md:grid-cols-2">
          {FAQ.map((id) => (
            <div key={id}>
              <dt className="text-[1.05rem] font-semibold text-foreground">{t(`pricing.faq.${id}.q`)}</dt>
              <dd className="mt-2 text-muted-foreground">{t(`pricing.faq.${id}.a`)}</dd>
            </div>
          ))}
        </dl>
      </Band>
    </>
  );
}
