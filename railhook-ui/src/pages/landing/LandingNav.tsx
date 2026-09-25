import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Github, Menu, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RailhookIcon } from '../../components/icons/RailhookIcon';
import LanguageSwitcher from '../../components/LanguageSwitcher';
import TryDemoButton from '../../components/site/TryDemoButton';
import { Button } from '../../components/ui/button';
import { useAuth } from '../../auth/auth.store';
import { docsUrl } from '../../lib/docsUrl';
import { REPO_URL } from './plans';

export default function LandingNav() {
  const { t, i18n } = useTranslation();
  const { isAuthenticated } = useAuth();
  const [open, setOpen] = useState(false);

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

  // "/#id" rather than "#id": this header is on every public page.
  const links: Array<{ label: string; to?: string; href?: string }> = [
    { label: t('landing.nav.product'), to: '/#product' },
    { label: t('landing.nav.selfHosting'), to: '/#self-host' },
    { label: t('landing.nav.pricing'), to: '/pricing' },
    { label: t('landing.nav.docs'), href: docsUrl(i18n.language) },
  ];
  const close = () => setOpen(false);

  return (
    <header className="sticky top-0 z-50 border-b border-rail bg-background">
      <div className="mx-auto flex h-14 max-w-[1336px] items-center gap-4 border-x border-rail px-4 max-sm:border-x-0 sm:px-7">
        <nav aria-label={t('landing.nav.label')} className="flex min-w-0 flex-1 items-center gap-4">
          <Link to="/" className="flex flex-none items-center gap-2 text-[22px] font-medium leading-none tracking-[-0.03em] text-foreground max-sm:min-h-10">
            <RailhookIcon className="h-[26px] w-[26px]" aria-hidden="true" />
            Railhook
          </Link>

          <ul className="mx-auto hidden items-center gap-8 text-[15px] text-[#333] dark:text-muted-foreground min-[901px]:flex">
            {links.map((link) => (
              <li key={link.label}>
                {link.to ? (
                  <Link to={link.to} className="transition-colors hover:text-foreground">
                    {link.label}
                  </Link>
                ) : (
                  <a href={link.href} className="transition-colors hover:text-foreground">
                    {link.label}
                  </a>
                )}
              </li>
            ))}
          </ul>

          <div className="ml-auto flex flex-none items-center gap-4 min-[901px]:ml-0">
            <LanguageSwitcher className="hidden h-9 min-[901px]:inline-flex" />
            {!isAuthenticated && (
              <Link to="/login" className="hidden text-[15px] text-[#333] transition-colors hover:text-foreground dark:text-muted-foreground min-[1101px]:block">
                {t('landing.nav.signIn')}
              </Link>
            )}
            <TryDemoButton label={t('landing.nav.liveDemo')} className="hidden min-[901px]:inline-flex" />
            <Button asChild className="max-sm:h-10 max-sm:px-3">
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
          className="-mr-2 inline-flex h-10 w-10 flex-none items-center justify-center text-foreground min-[901px]:hidden"
        >
          {open ? <X className="h-5 w-5" aria-hidden="true" /> : <Menu className="h-5 w-5" aria-hidden="true" />}
        </button>
      </div>

      {open && (
        <div id="landing-mobile-nav" className="h-[calc(100dvh-3.5rem)] overflow-y-auto border-t border-rail bg-background min-[901px]:hidden">
          <ul className="flex flex-col px-4 py-2 text-[15px] text-foreground sm:px-7">
            <li className="flex items-center justify-between gap-4 border-b border-rail py-3">
              <span className="mono-label">{t('settings.language')}</span>
              <LanguageSwitcher />
            </li>
            {links.map((link) => (
              <li key={link.label}>
                {link.to ? (
                  <Link to={link.to} onClick={close} className="block border-b border-rail py-3">
                    {link.label}
                  </Link>
                ) : (
                  <a href={link.href} onClick={close} className="block border-b border-rail py-3">
                    {link.label}
                  </a>
                )}
              </li>
            ))}
            <li>
              <a href={REPO_URL} target="_blank" rel="noopener noreferrer" className="flex items-center gap-2 border-b border-rail py-3">
                <Github className="h-4 w-4" aria-hidden="true" />
                {t('landing.nav.github')}
              </a>
            </li>
            {!isAuthenticated && (
              <li>
                <Link to="/login" onClick={close} className="block border-b border-rail py-3">
                  {t('landing.nav.signIn')}
                </Link>
              </li>
            )}
            <li className="pt-4">
              <TryDemoButton label={t('landing.nav.liveDemo')} className="w-full" />
            </li>
          </ul>
        </div>
      )}
    </header>
  );
}
