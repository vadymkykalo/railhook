import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Cookie, MessageCircle, X } from 'lucide-react';
import { contactDomain, webAnalyticsToken } from '../../lib/runtimeConfig';
import { readConsent, saveConsent } from '../../lib/consent';
import { RailhookIcon } from '../icons/RailhookIcon';
import { Button } from '../ui/button';
import { cn } from '../../lib/utils';
import ContactForm from './ContactForm';

/**
 * What floats over the public pages: the cookie notice and the "write to us" widget.
 *
 * <p>Both only where they mean something. The notice appears where the deployment runs analytics
 * (a web analytics token is set) — a self-hosted install sets no cookie beyond sign-in and has
 * nothing to ask about. The widget appears where there is a support address to write to (the
 * contact domain is set), and not on the contact page, which carries the same form in full.
 * Owned together because on a phone they share the bottom edge: the launcher rises above the
 * notice while the notice is up.
 */
export default function SiteOverlays() {
  const { pathname } = useLocation();
  const [consentOpen, setConsentOpen] = useState(false);

  useEffect(() => {
    if (!webAnalyticsToken() || readConsent()) return;
    // Not on first paint: the page is what the visitor came for.
    const timer = window.setTimeout(() => setConsentOpen(true), 900);
    return () => window.clearTimeout(timer);
  }, []);

  const showWidget = Boolean(contactDomain()) && pathname !== '/contact';

  return (
    <>
      {consentOpen && <CookieNotice onAnswer={() => setConsentOpen(false)} />}
      {showWidget && <ContactWidget raised={consentOpen} />}
    </>
  );
}

function CookieNotice({ onAnswer }: { onAnswer: () => void }) {
  const { t } = useTranslation();
  const answer = (value: 'accepted' | 'declined') => {
    saveConsent(value);
    onAnswer();
  };
  return (
    <section
      role="region"
      aria-labelledby="cookie-notice-title"
      className={cn(
        'fixed inset-x-4 bottom-4 z-40 sm:inset-x-auto sm:left-6 sm:bottom-6 sm:w-[25rem]',
        'rounded-2xl border border-rail bg-card/95 p-5 shadow-elevated-lg backdrop-blur-md',
        'animate-fade-in-up motion-reduce:animate-none',
      )}
    >
      <div className="flex items-start gap-3">
        <span aria-hidden="true" className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-accent text-primary">
          <Cookie className="h-[18px] w-[18px]" />
        </span>
        <div>
          <h2 id="cookie-notice-title" className="text-[15px] font-semibold text-foreground">{t('site.cookie.title')}</h2>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            {t('site.cookie.body')}{' '}
            <Link to="/privacy" className="font-medium text-primary hover:underline">{t('site.cookie.policy')}</Link>
          </p>
        </div>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-2">
        <Button variant="outline" size="sm" onClick={() => answer('declined')}>{t('site.cookie.decline')}</Button>
        <Button size="sm" onClick={() => answer('accepted')}>{t('site.cookie.accept')}</Button>
      </div>
    </section>
  );
}

function ContactWidget({ raised }: { raised: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  // Mounted on the first open (the challenge script loads only for someone who means to write),
  // then kept, so a half-written message survives closing the panel.
  const [started, setStarted] = useState(false);
  const launcher = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    setStarted(true);
    panel.current?.querySelector<HTMLInputElement>('input[type="email"]')?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        launcher.current?.focus();
      }
    };
    const onPointer = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!panel.current?.contains(target) && !launcher.current?.contains(target)) setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('pointerdown', onPointer);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('pointerdown', onPointer);
    };
  }, [open]);

  return (
    <div className={cn('fixed right-4 z-40 transition-[bottom] duration-300 sm:right-6 sm:bottom-6', raised ? 'bottom-52' : 'bottom-4')}>
      <div
        ref={panel}
        role="dialog"
        aria-modal="false"
        aria-labelledby="contact-widget-title"
        hidden={!open}
        className={cn(
          'absolute bottom-[calc(100%+0.75rem)] right-0 w-[calc(100vw-2rem)] max-w-[24rem] overflow-hidden',
          'rounded-2xl border border-rail bg-card shadow-elevated-lg',
          open && 'animate-scale-in origin-bottom-right motion-reduce:animate-none',
        )}
      >
        <div className="relative bg-primary px-5 pb-5 pt-4 text-primary-foreground">
          <div className="flex items-center gap-2.5">
            <span aria-hidden="true" className="grid h-8 w-8 place-items-center rounded-lg bg-primary-foreground/15">
              <RailhookIcon className="h-4 w-4" />
            </span>
            <span className="inline-flex items-center gap-1.5 text-sm font-semibold">
              {t('site.contact.team')}
              <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-emerald-300" />
            </span>
          </div>
          <h2 id="contact-widget-title" className="mt-3 font-display text-[1.25rem] font-bold tracking-[-0.02em]">
            {t('site.contact.widgetTitle')}
          </h2>
          <p className="mt-1 text-sm text-primary-foreground/85">{t('site.contact.widgetLead')}</p>
          <button
            type="button"
            onClick={() => {
              setOpen(false);
              launcher.current?.focus();
            }}
            aria-label={t('site.contact.close')}
            className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-md text-primary-foreground/80 transition-colors hover:bg-primary-foreground/15 hover:text-primary-foreground"
          >
            <X className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
        <div className="max-h-[min(34rem,calc(100vh-12rem))] overflow-y-auto p-5">
          {(open || started) && <ContactForm compact autoFocus={!started} />}
        </div>
      </div>

      <button
        ref={launcher}
        type="button"
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={() => setOpen((value) => !value)}
        className={cn(
          'group flex h-12 items-center gap-2 rounded-full bg-primary pl-3.5 pr-4 text-primary-foreground shadow-elevated-lg',
          'transition-transform duration-200 hover:-translate-y-0.5 motion-reduce:hover:translate-y-0',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
          'max-sm:w-12 max-sm:justify-center max-sm:p-0',
        )}
      >
        {open ? <X className="h-5 w-5" aria-hidden="true" /> : <MessageCircle className="h-5 w-5" aria-hidden="true" />}
        <span className="text-sm font-semibold max-sm:sr-only">{t('site.contact.launcher')}</span>
      </button>
    </div>
  );
}
