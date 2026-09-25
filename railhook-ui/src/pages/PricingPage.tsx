import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button } from '../components/ui/button';
import TryDemoButton from '../components/site/TryDemoButton';
import { useAuth } from '../auth/auth.store';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { useJsonLd } from '../hooks/useJsonLd';
import { cn } from '../lib/utils';
import { Band, PageIntro, SectionHeading, SectionLabel } from './landing/primitives';
import { docsUrl } from '../lib/docsUrl';
import { FREE_PLAN } from './landing/plans';

/** No competitor prices here: the owner's call. */

/** FAQ order is part of the page, and the FAQPage data follows it. */
const FAQ = ['limit', 'selfHost'] as const;

const CARD = 'flex flex-col gap-3 border bg-card p-6 sm:p-10';
const PRICE = 'text-[2.5rem] font-normal leading-none tracking-[-0.02em] text-foreground';
const TITLE = 'text-[1.625rem] font-normal tracking-[-0.02em] text-foreground';

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
      <PageIntro title={t('pricing.title')} lead={t('pricing.lead')} />

      <Band labelledBy="pricing-plans">
        <h2 id="pricing-plans" className="sr-only">{t('pricing.title')}</h2>
        <div className="grid gap-6 md:grid-cols-2">
          <article className={cn(CARD, 'border-foreground')}>
            <SectionLabel as="span">{t('pricing.cloud.pill')}</SectionLabel>
            <h3 className={cn(TITLE, 'mt-3')}>{t('pricing.cloud.title')}</h3>
            <p className={PRICE}><span className="mark-hl">{t('pricing.cloud.price')}</span></p>
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

          <article className={cn(CARD, 'border-rail')}>
            <SectionLabel as="span">{t('pricing.selfHosted.pill')}</SectionLabel>
            <h3 className={cn(TITLE, 'mt-3')}>{t('pricing.selfHosted.title')}</h3>
            <p className={PRICE}>{t('pricing.selfHosted.price')}</p>
            <p className="text-muted-foreground">{t('pricing.selfHosted.body')}</p>
            <div className="mt-auto pt-3">
              <Button asChild variant="outline" className="max-sm:w-full">
                <a href={docsUrl(i18n.language, 'self-hosting/overview')}>{t('pricing.selfHosted.cta')}</a>
              </Button>
            </div>
          </article>
        </div>
      </Band>

      <Band labelledBy="pricing-faq">
        <SectionHeading id="pricing-faq" title={t('pricing.faq.title')} />
        <dl className="grid border-t border-rail md:grid-cols-2 md:gap-x-10">
          {FAQ.map((id) => (
            <div key={id} className="border-b border-rail py-6">
              <dt className="text-[1.0625rem] font-medium text-foreground">{t(`pricing.faq.${id}.q`)}</dt>
              <dd className="mt-2 text-muted-foreground">{t(`pricing.faq.${id}.a`)}</dd>
            </div>
          ))}
        </dl>
      </Band>
    </>
  );
}
