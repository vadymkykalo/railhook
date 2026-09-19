import { useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  Activity,
  BookOpen,
  Bot,
  ChevronDown,
  Github,
  Menu,
  Radio,
  ShieldCheck,
  Terminal,
  X,
  type LucideIcon,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RailhookIcon } from '../../components/icons/RailhookIcon';
import LanguageSwitcher from '../../components/LanguageSwitcher';
import { Button } from '../../components/ui/button';
import { useAuth } from '../../auth/auth.store';
import { publicTesterEnabled, statusPageUrl } from '../../lib/runtimeConfig';
import { cn } from '../../lib/utils';
import { REPO_URL } from './plans';
import { WRAP } from './primitives';

/**
 * Eleven controls, at most: the logo, five places to go, the "Developers" menu, the language
 * switch, the repository, and the two ways in. What a developer reaches for — docs, the CLI, the
 * MCP server, the free tools, status — sits behind that one menu with a line on each, the way the
 * products people compare this with do it, rather than as five more words in a row.
 *
 * The language switch is here rather than in the footer, where it used to sit: a reader who
 * cannot read the page cannot be asked to scroll past all of it to say so, and on a long article
 * the footer is a page away. It is one control, not two presses — a segmented track showing both
 * choices — which is why the header's own count test treats it as one. The theme toggle stayed
 * in the footer: it is genuinely set once, and it is not a barrier to reading anything.
 * Below `lg` the header collapses to the menu button, and the switch travels into the panel with
 * everything else.
 *
 * Pricing is the page people look for first, and it covers both the free cloud plan and
 * self-hosting, so it took the place of a "Cloud" link that pointed at the same section as
 * "Self-host".
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
    { to: '/pricing', label: t('landing.nav.pricing') },
    { to: '/#run', label: t('landing.nav.selfHost') },
  ];
  const developers = useDeveloperLinks();
  const reading = [
    { to: '/blog', label: t('landing.nav.blog') },
    { to: '/about', label: t('landing.nav.about') },
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
              <DevelopersMenu links={developers} />
            </li>
            {reading.map((r) => (
              <li key={r.to}>
                <Link to={r.to} className={linkClass}>
                  {r.label}
                </Link>
              </li>
            ))}
          </ul>

          <div className="ml-auto flex flex-none items-center gap-2 text-[14.5px] font-medium sm:gap-4">
            <LanguageSwitcher className="hidden lg:inline-flex" />
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
            {/* First, not last: the wide header's switch is hidden at this width, and a reader
                who cannot read the page should meet it before the menu they cannot read. */}
            <li className="flex items-center justify-between gap-4 border-b border-rail py-3">
              <span className="mono-label">{t('settings.language')}</span>
              <LanguageSwitcher />
            </li>
            {sections.map((s) => (
              <li key={s.label}>
                <Link to={s.to} onClick={close} className="block border-b border-rail py-3">
                  {s.label}
                </Link>
              </li>
            ))}
            <li className="border-b border-rail py-3">
              <p className="mono-label mb-2">{t('landing.nav.developers')}</p>
              <ul className="grid gap-1">
                {developers.map((d) => (
                  <li key={d.href}>
                    <DeveloperLink link={d} onNavigate={close} />
                  </li>
                ))}
              </ul>
            </li>
            {reading.map((r) => (
              <li key={r.to}>
                <Link to={r.to} onClick={close} className="block border-b border-rail py-3">
                  {r.label}
                </Link>
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
          </ul>
        </div>
      )}
    </header>
  );
}

interface DeveloperLinkData {
  href: string;
  /** A route in this app rather than a page of the docs site or another host. */
  route?: boolean;
  external?: boolean;
  icon: LucideIcon;
  title: string;
  body: string;
}

function useDeveloperLinks(): DeveloperLinkData[] {
  const { t } = useTranslation();
  const status = statusPageUrl();
  return [
    { href: '/docs/', icon: BookOpen, title: t('landing.nav.devDocs'), body: t('landing.nav.devDocsBody') },
    { href: '/docs/tools/cli/', icon: Terminal, title: t('landing.nav.devCli'), body: t('landing.nav.devCliBody') },
    { href: '/docs/tools/mcp/', icon: Bot, title: t('landing.nav.devMcp'), body: t('landing.nav.devMcpBody') },
    ...(publicTesterEnabled()
      ? [{ href: '/tester', route: true, icon: Radio, title: t('landing.nav.devTester'), body: t('landing.nav.devTesterBody') }]
      : []),
    {
      href: '/tools/webhook-signature',
      route: true,
      icon: ShieldCheck,
      title: t('landing.nav.devVerifier'),
      body: t('landing.nav.devVerifierBody'),
    },
    ...(status
      ? [{ href: status, external: true, icon: Activity, title: t('landing.nav.devStatus'), body: t('landing.nav.devStatusBody') }]
      : []),
  ];
}

function DeveloperLink({ link, onNavigate }: { link: DeveloperLinkData; onNavigate?: () => void }) {
  const Icon = link.icon;
  const className = 'group flex items-start gap-3 rounded-lg p-2.5 transition-colors hover:bg-muted focus-visible:bg-muted';
  const content = (
    <>
      <span
        aria-hidden="true"
        className="grid h-8 w-8 flex-none place-items-center rounded-md bg-accent text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground"
      >
        <Icon className="h-4 w-4" />
      </span>
      <span className="min-w-0">
        <span className="block text-[14px] font-semibold text-foreground">{link.title}</span>
        <span className="block text-[13px] leading-snug text-muted-foreground">{link.body}</span>
      </span>
    </>
  );
  if (link.route) {
    return (
      <Link to={link.href} onClick={onNavigate} className={className}>
        {content}
      </Link>
    );
  }
  return (
    <a
      href={link.href}
      onClick={onNavigate}
      className={className}
      {...(link.external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
    >
      {content}
    </a>
  );
}

/** The "Developers" disclosure on a wide screen: opens on click, closes on Escape, outside or on arrival elsewhere. */
function DevelopersMenu({ links }: { links: DeveloperLinkData[] }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => setOpen(false), [pathname]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        button.current?.focus();
      }
    };
    const onPointer = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('pointerdown', onPointer);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('pointerdown', onPointer);
    };
  }, [open]);

  return (
    <div ref={root} className="relative">
      <button
        ref={button}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((v) => !v)}
        className={cn('inline-flex items-center gap-1 transition-colors hover:text-foreground', open && 'text-foreground')}
      >
        {t('landing.nav.developers')}
        <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', open && 'rotate-180')} aria-hidden="true" />
      </button>
      {open && (
        <div
          id={panelId}
          className="absolute left-1/2 top-[calc(100%+14px)] z-50 -ml-[17rem] w-[34rem] rounded-2xl border border-rail bg-card p-2 shadow-elevated-lg animate-scale-in motion-reduce:animate-none"
        >
          <ul className="grid grid-cols-2 gap-1">
            {links.map((link) => (
              <li key={link.href}>
                <DeveloperLink link={link} onNavigate={() => setOpen(false)} />
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
