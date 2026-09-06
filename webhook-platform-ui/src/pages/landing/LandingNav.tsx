import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Github, Menu, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RailhookIcon } from '../../components/icons/RailhookIcon';
import { Button } from '../../components/ui/button';
import ThemeToggle from '../../components/ThemeToggle';
import LanguageSwitcher from '../../components/LanguageSwitcher';
import { useAuth } from '../../auth/auth.store';
import { REPO_URL } from './plans';

/**
 * Five items, each naming a decision rather than a component of the system.
 *
 * The set this replaces was `How it works · Reliability · Dashboard · Features
 * · Pricing`: "Reliability" and "Dashboard" are attributes of the product, not
 * questions a buyer arrives with, and "Features" pointed at an anchor called
 * `#quickstart` in a file called `QuickstartSection`. Reliability and the
 * dashboard now live inside "How it works" and "Product" respectively, which is
 * where a reader looks for them.
 *
 * Pricing is a route, not an anchor: it is the link people paste into a chat.
 *
 * The anchors are `Link`s rather than bare `<a href>`s because this nav is also
 * mounted on /pricing and /contact, where an href to "/#security" is a full
 * document navigation. `LandingPage` scrolls to the hash on arrival, so a
 * client-side navigation lands in the same place at SPA cost.
 */
const LINKS = [
  { to: '/#capabilities', key: 'landing.nav.product' },
  { to: '/#how-it-works', key: 'landing.nav.directions' },
  { to: '/#security', key: 'landing.nav.security' },
] as const;

const ROUTES = [
  { to: '/pricing', key: 'landing.nav.pricing' },
  { to: '/docs', key: 'landing.nav.docs' },
] as const;

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

  return (
    <header className="sticky top-0 z-50 border-b border-rail bg-background/85 backdrop-blur-md">
      <nav className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-4 px-5 sm:px-6">
        <div className="flex items-center gap-7">
          <Link to="/" className="flex items-center gap-2.5">
            <span className="flex h-7 w-7 items-center justify-center rounded-md bg-primary">
              <RailhookIcon className="h-3.5 w-3.5 text-primary-foreground" />
            </span>
            <span className="text-[15px] font-semibold tracking-tight">Railhook</span>
          </Link>
          <ul className="hidden items-center gap-6 lg:flex">
            {LINKS.map((link) => (
              <li key={link.to}>
                <Link
                  to={link.to}
                  className="text-sm text-muted-foreground transition-colors hover:text-foreground"
                >
                  {t(link.key)}
                </Link>
              </li>
            ))}
            {ROUTES.map((route) => (
              <li key={route.to}>
                <Link
                  to={route.to}
                  className="text-sm text-muted-foreground transition-colors hover:text-foreground"
                >
                  {t(route.key)}
                </Link>
              </li>
            ))}
          </ul>
        </div>

        <div className="flex items-center gap-1.5">
          <div className="hidden sm:flex sm:items-center sm:gap-1.5">
            {/* The repository is a trust signal, not a step in the funnel: it
                stays reachable, at the weight of an icon rather than a button. */}
            <a
              href={REPO_URL}
              target="_blank"
              rel="noopener noreferrer"
              aria-label={t('landing.nav.github')}
              className="rounded-md p-2 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
            >
              <Github className="h-4 w-4" aria-hidden="true" />
            </a>
            <LanguageSwitcher />
            <ThemeToggle className="rounded-md p-2 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground" />
          </div>
          {isAuthenticated ? (
            <Link to="/admin/dashboard" className="hidden sm:block">
              <Button size="sm">{t('landing.nav.goToDashboard')}</Button>
            </Link>
          ) : (
            <>
              <Link
                to="/login"
                className="hidden px-2 text-sm text-muted-foreground transition-colors hover:text-foreground sm:block"
              >
                {t('landing.nav.signIn')}
              </Link>
              <Link to="/register" className="hidden sm:block">
                <Button size="sm">{t('landing.nav.getStarted')}</Button>
              </Link>
            </>
          )}
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            aria-expanded={open}
            aria-controls="landing-mobile-nav"
            aria-label={open ? t('landing.nav.closeMenu') : t('landing.nav.openMenu')}
            className="inline-flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground lg:hidden"
          >
            {open ? <X className="h-5 w-5" aria-hidden="true" /> : <Menu className="h-5 w-5" aria-hidden="true" />}
          </button>
        </div>
      </nav>

      {open && (
        <div id="landing-mobile-nav" className="border-t border-rail bg-background lg:hidden">
          <ul className="mx-auto flex max-w-6xl flex-col px-5 py-2 sm:px-6">
            {LINKS.map((link) => (
              <li key={link.to}>
                <Link
                  to={link.to}
                  onClick={() => setOpen(false)}
                  className="block border-b border-rail py-3 text-[15px] text-foreground"
                >
                  {t(link.key)}
                </Link>
              </li>
            ))}
            {ROUTES.map((route) => (
              <li key={route.to}>
                <Link
                  to={route.to}
                  onClick={() => setOpen(false)}
                  className="block border-b border-rail py-3 text-[15px] text-foreground"
                >
                  {t(route.key)}
                </Link>
              </li>
            ))}
            <li className="flex flex-col gap-2.5 py-4">
              {isAuthenticated ? (
                <Link to="/admin/dashboard" onClick={() => setOpen(false)}>
                  <Button className="w-full">{t('landing.nav.goToDashboard')}</Button>
                </Link>
              ) : (
                <>
                  <Link to="/register" onClick={() => setOpen(false)}>
                    <Button className="w-full">{t('landing.nav.getStarted')}</Button>
                  </Link>
                  <Link to="/login" onClick={() => setOpen(false)}>
                    <Button variant="outline" className="w-full">
                      {t('landing.nav.signIn')}
                    </Button>
                  </Link>
                </>
              )}
              <div className="flex items-center gap-2 pt-1 sm:hidden">
                <LanguageSwitcher />
                <ThemeToggle className="rounded-md p-2 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground" />
              </div>
            </li>
          </ul>
        </div>
      )}
    </header>
  );
}
