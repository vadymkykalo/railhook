import { Link } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import TryDemoButton from '../../components/site/TryDemoButton';
import { useAuth } from '../../auth/auth.store';
import { cn } from '../../lib/utils';
import { FREE_PLAN } from './plans';
import { IN, RICH, Rule } from './parts';

export default function FinalSection() {
  const { t, i18n } = useTranslation();
  const { isAuthenticated } = useAuth();

  return (
    <>
      <Rule />
      <section id="cloud" aria-labelledby="final-title" className={cn(IN, 'py-20 text-center md:py-[120px]')}>
        <h2 id="final-title" className="text-[38px] font-normal leading-[1.16] tracking-[-0.02em] text-foreground md:text-[56px]">
          <Trans i18nKey="landing.final.title" components={RICH} />
        </h2>
        <p className="mx-auto mt-5 max-w-[640px] text-base text-[#333] dark:text-muted-foreground">
          {t('landing.final.body', {
            events: new Intl.NumberFormat(i18n.language).format(FREE_PLAN.events),
            projects: FREE_PLAN.projects,
            retention: FREE_PLAN.retention,
          })}
        </p>
        <div className="mt-9 flex flex-wrap justify-center gap-4">
          <Button asChild size="lg">
            {isAuthenticated ? (
              <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
            ) : (
              <Link to="/register">{t('landing.hero.startFree')}</Link>
            )}
          </Button>
          <TryDemoButton size="lg" />
        </div>
      </section>
    </>
  );
}
