import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import { useAuth } from '../../auth/auth.store';
import InstallCommand from './InstallCommand';
import { WRAP } from './primitives';

/** The end of the page repeats both ways in: sign up, or copy the command. */
export default function ClosingSection() {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();

  return (
    <section aria-labelledby="closing-title" className="border-t border-rail py-16 text-center sm:py-[84px]">
      <div className={WRAP}>
        <h2
          id="closing-title"
          className="mx-auto max-w-[18ch] font-display text-[clamp(1.6rem,3.4vw,2.6rem)] font-bold leading-[1.1] tracking-[-0.025em] text-foreground [text-wrap:balance]"
        >
          {t('landing.closing.title')}
        </h2>
        <div className="mt-7 flex justify-center">
          <Button asChild size="lg">
            {isAuthenticated ? (
              <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
            ) : (
              <Link to="/register">{t('landing.hero.startFree')}</Link>
            )}
          </Button>
        </div>
        <InstallCommand className="mt-7" />
      </div>
    </section>
  );
}
