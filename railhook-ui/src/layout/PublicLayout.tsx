import { useId, useLayoutEffect, type ReactNode } from 'react';
import { Link, Outlet, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Github, Mail, type LucideIcon } from 'lucide-react';
import { contactDomain, publicBlogEnabled, publicTesterEnabled, statusPageUrl } from '../lib/runtimeConfig';
import { RailhookIcon } from '../components/icons/RailhookIcon';
import ThemeToggle from '../components/ThemeToggle';
import LandingNav from '../pages/landing/LandingNav';
import SiteOverlays from '../components/site/SiteOverlays';
import { REPO_URL } from '../pages/landing/plans';
import { WRAP } from '../pages/landing/primitives';

/**
 * A new public page starts at the top of itself.
 *
 * <p>`createBrowserRouter` leaves the scroll offset alone across a navigation, which is right
 * for an app shell whose panes scroll independently and wrong for a set of long marketing
 * pages: following a link from halfway down the home page landed on the next page at the
 * same offset. The page looked like it had lost its top.
 *
 * <p>The landing page keeps its own effect because it has something extra to do — the nav
 * links to `#run` and friends, and a hash has to win over this. Scrolling on layout rather
 * than after paint so the jump is never drawn.
 */
function useScrollToTopOnNavigate() {
  const { pathname, hash } = useLocation();
  useLayoutEffect(() => {
    if (hash) return;
    window.scrollTo({ top: 0, behavior: 'auto' });
  }, [pathname, hash]);
}

/**
 * The chrome every public page shares: the header, then one 1336px frame with hairlines at both
 * edges holding the page and the footer. `nav` is opt-in because the documentation brings its
 * own; the footer is not.
 */
export default function PublicLayout({ nav = true }: { nav?: boolean }) {
  useScrollToTopOnNavigate();
  return (
    <div className="flex min-h-screen flex-col">
      {nav && <LandingNav />}
      <div className="mx-auto flex w-full max-w-[1336px] flex-1 flex-col border-x border-rail max-sm:border-x-0">
        <div className="flex-1">
          <Outlet />
        </div>
        <Footer />
      </div>
      <SiteOverlays />
    </div>
  );
}

/* Below sm a link is a 40px row, so a thumb hits the one it meant; from sm the column is as dense
   as it always was. */
const LINK = 'text-[14.5px] text-[#555] transition-colors hover:text-foreground dark:text-muted-foreground max-sm:flex max-sm:min-h-10 max-sm:items-center';

function Column({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h2 className="mb-3.5 text-sm font-medium leading-none text-foreground max-sm:mb-1">{title}</h2>
      <ul className="space-y-2.5 max-sm:space-y-0">{children}</ul>
    </div>
  );
}

function RouteLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <li>
      <Link to={to} className={LINK}>
        {children}
      </Link>
    </li>
  );
}

/** Docs are a separate static site, so a full navigation, not a router link. */
function PageLink({ href, external = false, children }: { href: string; external?: boolean; children: ReactNode }) {
  return (
    <li>
      <a href={href} className={LINK} {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}>
        {children}
      </a>
    </li>
  );
}

/**
 * Only accounts that exist: the repository always, support mail only where the deployment has a
 * contact domain — the same rule as the contact page, since a self-hosted install has no support
 * desk. Another account is one more entry in `links`.
 */
function ConnectWithUs() {
  const { t } = useTranslation();
  const captionId = useId();
  const domain = contactDomain();
  const links: { key: string; href: string; label: string; icon: LucideIcon; external: boolean }[] = [
    { key: 'github', href: REPO_URL, label: t('footer.connectGithub'), icon: Github, external: true },
    ...(domain
      ? [{ key: 'email', href: `mailto:support@${domain}`, label: t('footer.connectEmail'), icon: Mail, external: false }]
      : []),
  ];
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
      <p id={captionId} className="text-sm text-muted-foreground">
        {t('footer.connect')}
      </p>
      <ul aria-labelledby={captionId} className="-ml-2 flex items-center gap-1">
        {links.map(({ key, href, label, icon: Icon, external }) => (
          <li key={key}>
            <a
              href={href}
              aria-label={label}
              title={label}
              className="grid h-9 w-9 place-items-center text-muted-foreground transition-colors hover:text-foreground max-sm:h-10 max-sm:w-10"
              {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
            >
              <Icon className="h-5 w-5" aria-hidden="true" />
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * The theme toggle lives here rather than in the header: it is set once, and the header is kept
 * to the places a reader goes. The language switch used to sit beside it and does not any more —
 * a reader who cannot read the page should not have to scroll past all of it to say so, so it
 * moved into the header (`LandingNav`).
 */
export function Footer() {
  const { t } = useTranslation();
  return (
    <footer className="lp-rule bg-background">
      <div className={`${WRAP} pb-12 pt-14`}>
        {/* Two columns of links on a phone, with the brand across the top, instead of one long list. */}
        <div className="grid gap-[30px] max-sm:grid-cols-2 max-sm:gap-x-6 max-sm:gap-y-8 sm:grid-cols-2 lg:grid-cols-[1.4fr_repeat(4,minmax(0,1fr))]">
          <div className="max-sm:col-span-2">
            <Link to="/" className="mb-3.5 flex items-center gap-2 text-[22px] font-medium leading-none tracking-[-0.03em] text-foreground max-sm:min-h-10">
              <RailhookIcon className="h-[22px] w-[22px]" aria-hidden="true" />
              Railhook
            </Link>
            <p className="max-w-[16rem] text-[13.5px] leading-relaxed text-[#777] dark:text-muted-foreground">{t('footer.tagline')}</p>
          </div>
          <Column title={t('footer.product')}>
            <RouteLink to="/#product">{t('footer.overview')}</RouteLink>
            <RouteLink to="/#cloud">{t('footer.cloud')}</RouteLink>
            <RouteLink to="/#self-host">{t('footer.selfHost')}</RouteLink>
            <RouteLink to="/pricing">{t('footer.pricing')}</RouteLink>
            {publicTesterEnabled() && <RouteLink to="/tester">{t('footer.tester')}</RouteLink>}
            <RouteLink to="/tools/webhook-signature">{t('footer.signatureVerifier')}</RouteLink>
            <RouteLink to="/login">{t('footer.signIn')}</RouteLink>
          </Column>
          <Column title={t('footer.docs')}>
            <PageLink href="/docs/">{t('footer.documentation')}</PageLink>
            <PageLink href="/docs/start/quickstart/">{t('footer.quickstart')}</PageLink>
            <PageLink href="/docs/self-hosting/overview/">{t('footer.selfHosting')}</PageLink>
            <PageLink href="/docs/api-reference/">{t('footer.apiReference')}</PageLink>
            <PageLink href="/docs/tools/mcp/">{t('footer.mcp')}</PageLink>
            <PageLink href="/docs/tools/cli/">{t('footer.cli')}</PageLink>
          </Column>
          <Column title={t('footer.community')}>
            <PageLink href={REPO_URL} external>{t('footer.github')}</PageLink>
            <PageLink href={`${REPO_URL}/issues`} external>{t('footer.issues')}</PageLink>
            <PageLink href={`${REPO_URL}/releases`} external>{t('footer.releases')}</PageLink>
          </Column>
          <Column title={t('footer.company')}>
            <RouteLink to="/about">{t('footer.about')}</RouteLink>
            {publicBlogEnabled() && <RouteLink to="/blog">{t('footer.blog')}</RouteLink>}
            <RouteLink to="/security">{t('footer.security')}</RouteLink>
            {statusPageUrl() && <PageLink href={statusPageUrl()!} external>{t('footer.status')}</PageLink>}
            <RouteLink to="/contact">{t('footer.talkToUs')}</RouteLink>
            <RouteLink to="/privacy">{t('footer.privacy')}</RouteLink>
            <RouteLink to="/terms">{t('footer.terms')}</RouteLink>
          </Column>
        </div>
        <div className="mt-10 flex flex-wrap items-center justify-between gap-4 border-t border-rail pt-6">
          <div className="flex flex-col gap-3">
            <ConnectWithUs />
            <p className="font-mono text-xs text-muted-foreground">{t('footer.copyright', { year: new Date().getFullYear() })}</p>
          </div>
          <div className="flex items-center gap-3">
            <a
              href="https://www.saashub.com/railhook?utm_source=badge&utm_campaign=badge&utm_content=railhook&badge_variant=color&badge_kind=approved"
              target="_blank"
              rel="noopener noreferrer"
              className="opacity-80 transition-opacity hover:opacity-100"
            >
              <img src="/badges/saashub-approved-color.png" alt="Railhook on SaaSHub" width={150} height={50} className="dark:hidden" />
              <img src="/badges/saashub-approved-dark.png" alt="Railhook on SaaSHub" width={150} height={50} className="hidden dark:block" />
            </a>
            <ThemeToggle className="border border-input p-2 text-muted-foreground transition-colors hover:border-foreground hover:text-foreground max-sm:grid max-sm:h-10 max-sm:w-10 max-sm:place-items-center" />
          </div>
        </div>
      </div>
    </footer>
  );
}
