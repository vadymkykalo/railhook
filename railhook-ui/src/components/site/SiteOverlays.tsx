import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Cookie, MessageCircle, X } from 'lucide-react';
import { contactDomain, webAnalyticsToken } from '../../lib/runtimeConfig';
import { markNoticeSeen, noticeSeen } from '../../lib/consent';
import { RailhookIcon } from '../icons/RailhookIcon';
import { Button } from '../ui/button';
import { cn } from '../../lib/utils';
import ContactForm from './ContactForm';

/** Owned together: on a phone they share the bottom edge, so the launcher rises above the notice. */
export default function SiteOverlays() {
  const [noticeOpen, setNoticeOpen] = useState(false);

  useEffect(() => {
    if (!webAnalyticsToken() || noticeSeen()) return;
    const timer = window.setTimeout(() => setNoticeOpen(true), 900);
    return () => window.clearTimeout(timer);
  }, []);

  const showWidget = Boolean(contactDomain());

  return (
    <>
      {noticeOpen && <CookieNotice onAnswer={() => setNoticeOpen(false)} />}
      {showWidget && <ContactWidget raised={noticeOpen} />}
    </>
  );
}

function CookieNotice({ onAnswer }: { onAnswer: () => void }) {
  const { t } = useTranslation();
  const dismiss = () => {
    markNoticeSeen();
    onAnswer();
  };
  return (
    <section
      role="region"
      aria-labelledby="cookie-notice-title"
      className={cn(
        'fixed inset-x-4 bottom-4 z-40 sm:inset-x-auto sm:left-6 sm:bottom-6 sm:w-[25rem]',
        'border border-rail bg-card/95 p-5 shadow-elevated-lg backdrop-blur-md',
        'animate-fade-in-up motion-reduce:animate-none',
      )}
    >
      <div className="flex items-start gap-3">
        <span aria-hidden="true" className="grid h-9 w-9 shrink-0 place-items-center bg-accent text-accent-foreground">
          <Cookie className="h-[18px] w-[18px]" />
        </span>
        <div>
          <h2 id="cookie-notice-title" className="text-[15px] font-medium text-foreground">{t('site.cookie.title')}</h2>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            {t('site.cookie.body')}{' '}
            <Link to="/privacy" className="font-medium link-ink">
              {t('site.cookie.policy')}
            </Link>
          </p>
        </div>
      </div>
      {/* One button: as a second button the Ukrainian policy label overflowed on a phone. */}
      <div className="mt-4 flex justify-end">
        <Button size="sm" onClick={dismiss} className="max-sm:w-full">{t('site.cookie.ok')}</Button>
      </div>
    </section>
  );
}

function ContactWidget({ raised }: { raised: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  // Mounted on first open (loads the challenge script only then), then kept so a draft survives closing.
  const [started, setStarted] = useState(false);
  const launcher = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    setStarted(true);
    panel.current?.querySelector<HTMLInputElement>('input[type="email"]')?.focus();
    // With the keyboard up, a scrolled page pushed the sheet's close button off screen.
    const phone = window.matchMedia('(max-width: 639px)').matches;
    const previousOverflow = document.body.style.overflow;
    if (phone) document.body.style.overflow = 'hidden';
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
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  return (
    // Above the sticky header (z-50), which otherwise covered the sheet's close button.
    <div className={cn('fixed right-4 z-[60] transition-[bottom] duration-300 sm:right-6 sm:bottom-6', raised ? 'bottom-52' : 'bottom-4')}>
      <div
        ref={panel}
        role="dialog"
        aria-modal="false"
        aria-labelledby="contact-widget-title"
        hidden={!open}
        className={cn(
          // `hidden` as a class: a responsive display:flex beats [hidden] and the invisible sheet ate every tap.
          open
            ? 'max-sm:fixed max-sm:inset-0 max-sm:flex max-sm:h-[100dvh] max-sm:w-full max-sm:max-w-none max-sm:flex-col'
            : 'hidden',
          'sm:absolute sm:bottom-[calc(100%+0.75rem)] sm:right-0 sm:h-auto sm:w-[calc(100vw-2rem)] sm:max-w-[24rem] sm:overflow-hidden',
          'border border-rail bg-card shadow-elevated-lg max-sm:border-0',
          open && 'sm:animate-scale-in sm:origin-bottom-right motion-reduce:animate-none',
        )}
      >
        <div className="relative flex-none bg-primary px-5 pb-5 pt-4 text-primary-foreground max-sm:pt-[max(1rem,env(safe-area-inset-top))]">
          <div className="flex items-center gap-2.5">
            <RailhookIcon className="h-5 w-5" aria-hidden="true" />
            <span className="inline-flex items-center gap-1.5 text-sm font-medium">
              {t('site.contact.team')}
              <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-highlight" />
            </span>
          </div>
          <h2 id="contact-widget-title" className="mt-3 text-[1.25rem] font-medium tracking-[-0.02em]">
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
            className="absolute right-3 top-3 grid h-10 w-10 place-items-center text-primary-foreground/80 transition-colors hover:bg-primary-foreground/15 hover:text-primary-foreground max-sm:top-[max(0.75rem,env(safe-area-inset-top))]"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>
        <div className="overflow-y-auto p-5 max-sm:flex-1 max-sm:pb-[max(1.25rem,env(safe-area-inset-bottom))] sm:max-h-[min(34rem,calc(100vh-12rem))]">
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
          'group flex h-11 items-center gap-2 bg-primary pl-3.5 pr-4 text-primary-foreground shadow-elevated',
          open && 'max-sm:hidden',
          'transition-colors duration-200 hover:bg-primary-hover',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
          'max-sm:w-11 max-sm:justify-center max-sm:p-0',
        )}
      >
        {open ? <X className="h-5 w-5" aria-hidden="true" /> : <MessageCircle className="h-5 w-5" aria-hidden="true" />}
        <span className="text-sm font-medium max-sm:sr-only">{t('site.contact.launcher')}</span>
      </button>
    </div>
  );
}
