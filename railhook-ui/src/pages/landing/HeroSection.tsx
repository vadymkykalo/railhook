import { Link } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import { useAuth } from '../../auth/auth.store';
import InstallCommand from './InstallCommand';
import RailMap from './RailMap';
import { WRAP } from './primitives';

/**
 * The outcome as the headline, both ways to get it as the two buttons, and the install command
 * right there for the reader who has already decided. The map underneath shows the headline
 * happening: one delivery fails, waits, and still arrives.
 */
export default function HeroSection() {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();

  return (
    <section aria-labelledby="hero-title" className="pt-14 sm:pt-[76px]">
      <div className={`${WRAP} text-center`}>
        <h1
          id="hero-title"
          className="mx-auto max-w-[14ch] font-display text-[clamp(2.3rem,5.6vw,4.3rem)] font-extrabold leading-[1.04] tracking-[-0.035em] text-foreground [text-wrap:balance]"
        >
          <Trans i18nKey="landing.hero.title" components={{ em: <em className="not-italic text-primary" /> }} />
        </h1>
        <p className="mx-auto mt-5 max-w-[56ch] text-[clamp(1.02rem,1.6vw,1.2rem)] text-muted-foreground [text-wrap:balance]">
          {t('landing.hero.lead')}
        </p>

        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Button asChild size="lg">
            {isAuthenticated ? (
              <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
            ) : (
              <Link to="/register">{t('landing.hero.startFree')}</Link>
            )}
          </Button>
          <Button asChild size="lg" variant="outline">
            <a href="#install">{t('landing.hero.install')}</a>
          </Button>
        </div>
        {!isAuthenticated && <p className="mt-3 text-sm text-muted-foreground">{t('landing.hero.cloudNote')}</p>}

        <InstallCommand label id="install" className="mt-10" />
      </div>

      {/* Not on a phone: at that width the map is either a sideways scroll or unreadably small,
          and the directions cards directly below say the same thing in words. */}
      <div className="mt-11 hidden border-t border-rail bg-gradient-to-b from-background to-muted pb-8 pt-6 sm:block">
        <div className={WRAP}>
          <RailMap />
        </div>
      </div>
    </section>
  );
}
