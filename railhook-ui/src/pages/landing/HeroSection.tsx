import { Link } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import TryDemoButton from '../../components/site/TryDemoButton';
import { useAuth } from '../../auth/auth.store';
import HeroBackdrop from './HeroBackdrop';
import InstallCommand from './InstallCommand';
import RailMap from './RailMap';
import { WRAP } from './primitives';

/**
 * The outcome as the headline, both ways to get it as the two buttons, and the install command
 * right there for the reader who has already decided. Behind them, quietly, deliveries travel the
 * rails; the map underneath shows the headline happening: one delivery fails, waits, and still
 * arrives.
 */
export default function HeroSection() {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();

  return (
    <section aria-labelledby="hero-title">
      <div className="relative isolate overflow-hidden pb-11 pt-14 sm:pt-[76px]">
      <HeroBackdrop />
      <div className={`${WRAP} text-center`}>
        <h1
          id="hero-title"
          className="mx-auto max-w-[14ch] font-display text-[2.7rem] font-extrabold leading-[1] tracking-[-0.04em] text-foreground [text-wrap:balance] sm:text-[clamp(2.3rem,5.6vw,4.3rem)] sm:leading-[1.04] sm:tracking-[-0.035em]"
        >
          <Trans i18nKey="landing.hero.title" components={{ em: <em className="not-italic text-primary" /> }} />
        </h1>
        <p className="mx-auto mt-5 max-w-[56ch] text-[clamp(1.02rem,1.6vw,1.2rem)] text-muted-foreground [text-wrap:balance]">
          {t('landing.hero.lead')}
        </p>

        {/* On a phone the two ways in are one column of equal buttons, full width; from sm they sit
            side by side as they always have. */}
        <div className="mt-8 grid gap-3 sm:flex sm:flex-wrap sm:justify-center">
          <Button asChild size="lg" className="max-sm:h-12 max-sm:w-full">
            {isAuthenticated ? (
              <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
            ) : (
              <Link to="/register">{t('landing.hero.startFree')}</Link>
            )}
          </Button>
          <Button asChild size="lg" variant="outline" className="max-sm:h-12 max-sm:w-full">
            <a href="#install">{t('landing.hero.install')}</a>
          </Button>
          <TryDemoButton size="lg" className="max-sm:h-12 max-sm:w-full" />
        </div>
        {!isAuthenticated && <p className="mt-3 text-sm text-muted-foreground">{t('landing.hero.cloudNote')}</p>}

        <InstallCommand label id="install" className="mt-10" />
      </div>
      </div>

      {/* Not on a phone: at that width the map is either a sideways scroll or unreadably small,
          and the directions cards directly below say the same thing in words. */}
      <div className="hidden border-t border-rail bg-gradient-to-b from-background to-muted pb-8 pt-6 sm:block">
        <div className={WRAP}>
          <RailMap />
        </div>
      </div>
    </section>
  );
}
