import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Github, Menu, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RailhookIcon } from '../../components/icons/RailhookIcon';
import { Button } from '../../components/ui/button';
import { useAuth } from '../../auth/auth.store';
import { REPO_URL } from './plans';
import { WRAP } from './primitives';

/**
 * Eight things to press, at most: the logo, four places to go, the repository, and the two ways
 * in. The header this replaced had eleven, including the language and theme switches — those
 * are set once and now live in the footer.
 *
 * Section links are router `Link`s to "/#id" because this header is also mounted on /contact,
 * where a bare "#run" would point nowhere; `LandingPage` scrolls to the hash on arrival. Docs is a
 * plain anchor: it is served as its own static site, not a route in this app.
 *
 * The menu button sits outside the `<nav>` landmark: it is not a destination, and on a wide
 * screen it is not there at all.
 */
export default function LandingNav() {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();
  const [open, setOpen] = useState(false);

  // The panel is a full-height sheet on small screens; letting the page behind
  // it scroll makes it feel detached from the tap that opened it.
  useEffect(() => {
    if (!open) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [open]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const sections = [
    { to: '/#product', label: t('landing.nav.product') },
    { to: '/#run', label: t('landing.nav.cloud') },
    { to: '/#run', label: t('landing.nav.selfHost') },
  ];
  const linkClass = 'transition-colors hover:text-foreground';
  const close = () => setOpen(false);

  return (
    <header className="sticky top-0 z-50 border-b border-rail bg-background/90 backdrop-blur-md">
      <div className={`${WRAP} flex h-16 items-center gap-2`}>
        <nav aria-label={t('landing.nav.label')} className="flex min-w-0 flex-1 items-center gap-7">
          <Link to="/" className="flex flex-none items-center gap-2.5 text-[17px] font-bold tracking-[-0.01em] text-foreground max-sm:min-h-10">
            <span aria-hidden="true" className="grid h-[30px] w-[30px] place-items-center rounded-lg bg-primary">
              <RailhookIcon className="h-[18px] w-[18px] text-primary-foreground" />
            </span>
            Railhook
          </Link>

          <ul className="hidden items-center gap-[22px] text-[14.5px] font-medium text-muted-foreground lg:flex">
            {sections.map((s) => (
              <li key={s.label}>
                <Link to={s.to} className={linkClass}>
                  {s.label}
                </Link>
              </li>
            ))}
            <li>
              <a href="/docs/" className={linkClass}>
                {t('landing.nav.docs')}
              </a>
            </li>
          </ul>

          <div className="ml-auto flex flex-none items-center gap-2 text-[14.5px] font-medium sm:gap-4">
            <a
              href={REPO_URL}
              target="_blank"
              rel="noopener noreferrer"
              aria-label={t('landing.nav.github')}
              className="hidden h-[34px] w-[34px] place-items-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground sm:grid"
            >
              <Github className="h-[19px] w-[19px]" aria-hidden="true" />
            </a>
            {!isAuthenticated && (
              <Link to="/login" className={`hidden text-foreground sm:block ${linkClass}`}>
                {t('landing.nav.signIn')}
              </Link>
            )}
            <Button asChild size="sm" className="max-sm:h-10 max-sm:px-3.5">
              {isAuthenticated ? (
                <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
              ) : (
                <Link to="/register">{t('landing.nav.startFree')}</Link>
              )}
            </Button>
          </div>
        </nav>

        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          aria-controls="landing-mobile-nav"
          aria-label={open ? t('landing.nav.closeMenu') : t('landing.nav.openMenu')}
          className="inline-flex h-9 w-9 flex-none items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground max-sm:h-10 max-sm:w-10 lg:hidden"
        >
          {open ? <X className="h-5 w-5" aria-hidden="true" /> : <Menu className="h-5 w-5" aria-hidden="true" />}
        </button>
      </div>

      {open && (
        <div id="landing-mobile-nav" className="h-[calc(100dvh-4rem)] overflow-y-auto border-t border-rail bg-background lg:hidden">
          <ul className={`${WRAP} flex flex-col py-2 text-[15px] text-foreground`}>
            {sections.map((s) => (
              <li key={s.label}>
                <Link to={s.to} onClick={close} className="block border-b border-rail py-3">
                  {s.label}
                </Link>
              </li>
            ))}
            <li>
              <a href="/docs/" className="block border-b border-rail py-3">
                {t('landing.nav.docs')}
              </a>
            </li>
            <li>
              <a href={REPO_URL} target="_blank" rel="noopener noreferrer" className="flex items-center gap-2 border-b border-rail py-3">
                <Github className="h-4 w-4" aria-hidden="true" />
                {t('landing.nav.github')}
              </a>
            </li>
            {!isAuthenticated && (
              <li>
                <Link to="/login" onClick={close} className="block py-3">
                  {t('landing.nav.signIn')}
                </Link>
              </li>
            )}
          </ul>
        </div>
      )}
    </header>
  );
}
