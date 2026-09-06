import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import { useAuth } from '../../auth/auth.store';
import { RailRule, Reveal } from './primitives';
import { CONTACT_PATH } from './plans';

/**
 * The last thing on the page, and the only repeat of the hero's ask.
 *
 * A closing CTA was removed once before because it restated the pricing
 * section's self-host/cloud fork one screen after making it. This one does not
 * fork: the page's job is a free account, so both buttons here point at the
 * funnel — start, or ask a person. Self-hosting has already had its own card,
 * its own price and its own two buttons in the section above.
 */
export default function ClosingSection() {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();

  return (
    <section className="relative">
      <RailRule />
      <div className="mx-auto max-w-6xl px-5 py-16 sm:px-6 lg:py-20">
        <Reveal>
          <div className="max-w-2xl">
            <h2 className="font-display text-3xl leading-[1.1] tracking-tight text-foreground sm:text-headline">
              {t('landing.closing.title')}
            </h2>
            <p className="mt-4 text-body-lg text-muted-foreground">{t('landing.closing.body')}</p>
            <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center">
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
                      {t('landing.closing.ctaPrimary')} <ArrowRight className="h-4 w-4" aria-hidden="true" />
                    </Button>
                  </Link>
                  <Link to={CONTACT_PATH}>
                    <Button size="lg" variant="outline" className="w-full sm:w-auto">
                      {t('landing.closing.ctaSecondary')}
                    </Button>
                  </Link>
                </>
              )}
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
