import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import { useAuth } from '../../auth/auth.store';
import AmbientDelivery from './AmbientDelivery';
import DeliveryFlight from './DeliveryFlight';
import { Reveal } from './primitives';
import { FREE_PLAN } from './plans';

/**
 * The headline is the outcome, not the topology.
 *
 * It used to lead with "on your own servers" and follow with four technical
 * facts in one breath — HMAC, the ladder, the DLQ, the two directions. Where
 * the software runs is an implementation detail; not losing a webhook is the
 * reason to buy. The proof still appears, one rung down: the chips under the
 * fold carry it, and the sections below spell it out once each.
 *
 * Both buttons stay inside the funnel: the account, then the documentation.
 * The repository is a trust signal, not a step, so it lives as a mark in the
 * nav — a visitor deciding whether to buy should not be handed a git clone as
 * one of their two choices.
 *
 * The free-plan numbers are interpolated from FREE_PLAN rather than written
 * into the sentence, because the same figures are rendered again in the pricing
 * grid and a hand-typed copy of them drifts on the first plan change.
 */
export default function HeroSection() {
  const { t, i18n } = useTranslation();
  const { isAuthenticated } = useAuth();
  const events = new Intl.NumberFormat(i18n.language).format(FREE_PLAN.events);

  return (
    <section className="relative overflow-hidden">
      <AmbientDelivery />
      <div className="relative mx-auto max-w-6xl px-5 pb-14 pt-14 sm:px-6 lg:pb-20 lg:pt-20">
        <div className="grid items-center gap-12 lg:grid-cols-[minmax(0,0.95fr)_minmax(0,1.05fr)] lg:gap-14">
          <div>
            <p className="mono-label">{t('landing.hero.eyebrow')}</p>
            <h1 className="mt-4 max-w-[14ch] font-display text-[2.25rem] leading-[1.05] tracking-[-0.03em] text-foreground sm:text-[2.75rem] lg:max-w-[15ch] lg:text-[3.25rem]">
              {t('landing.hero.title')}
            </h1>
            <p className="mt-5 max-w-xl text-body-lg text-muted-foreground">{t('landing.hero.subtitle')}</p>

            {/* flex-wrap, not just sm:flex-row: the Ukrainian labels are half
                again as long as the English ones and pushed the second button
                under the panel in the next column. */}
            <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center">
              {isAuthenticated ? (
                <Link to="/admin/dashboard">
                  <Button size="lg" className="w-full sm:w-auto">
                    {t('landing.nav.goToDashboard')} <ArrowRight className="h-4 w-4" aria-hidden="true" />
                  </Button>
                </Link>
              ) : (
                <>
                  <Link to="/register">
                    <Button size="lg" className="w-full sm:w-auto">
                      {t('landing.hero.ctaPrimary')} <ArrowRight className="h-4 w-4" aria-hidden="true" />
                    </Button>
                  </Link>
                  <Link to="/docs">
                    <Button size="lg" variant="outline" className="w-full sm:w-auto">
                      {t('landing.hero.ctaSecondary')}
                    </Button>
                  </Link>
                </>
              )}
            </div>
            <p className="mt-3 text-sm text-muted-foreground">{t('landing.hero.ctaNote', { events })}</p>

            <ul className="mt-8 flex flex-col gap-2 border-t border-rail pt-6 font-mono text-[12px] text-muted-foreground sm:flex-row sm:flex-wrap sm:gap-x-6">
              <li>{t('landing.hero.chip1')}</li>
              <li>{t('landing.hero.chip2')}</li>
              <li>{t('landing.hero.chip3')}</li>
            </ul>
          </div>

          <Reveal delay={120} className="lg:pl-4">
            <DeliveryFlight />
          </Reveal>
        </div>
      </div>
    </section>
  );
}
