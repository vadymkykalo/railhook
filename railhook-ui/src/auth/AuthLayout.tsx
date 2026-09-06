import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RailhookIcon } from '../components/icons/RailhookIcon';
import AttemptRail from '../components/AttemptRail';
import LanguageSwitcher from '../components/LanguageSwitcher';

/**
 * One shell for all seven auth screens, which each used to draw their own.
 *
 * The left panel shows the product's actual material — a signed request and the
 * ladder it was delivered on — rather than a gradient and three adjectives. It
 * is the first thing a developer sees, and a real signature header says more
 * about what this is than any tagline.
 */

/**
 * The headers a real outgoing attempt carries, copied from
 * OutgoingAttemptStore: X-Signature, X-Event-Id, X-Delivery-Id, X-Timestamp,
 * X-Sequence-Number, Idempotency-Key. The signature format is
 * `t=<unix-ms>,v1=<hex>` from WebhookSignatureUtils.buildSignatureHeader.
 *
 * This panel used to invent `x-railhook-signature: sha256=…` and
 * `x-railhook-attempt: 4 of 8` — headers the product does not send, in a
 * format the docs teach differently, over an attempt count the outgoing ladder
 * does not have. It is the first thing a developer sees on all seven auth
 * screens; a fabricated wire format is the most expensive kind of wrong.
 */
const SAMPLE = [
  ['POST', '/webhooks/orders'],
  ['host', 'api.acme.com'],
  ['X-Event-Id', 'evt_9c41e8f2'],
  ['X-Signature', 't=1787519666714,v1=9f2b…c41e'],
  ['X-Timestamp', '1787519666714'],
] as const;

export default function AuthLayout({
  title, subtitle, children, footer,
}: {
  title: string;
  subtitle?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const { t } = useTranslation();

  return (
    <div className="flex min-h-screen">
      <aside className="surface-ink relative hidden overflow-hidden lg:flex lg:w-[46%] lg:flex-col lg:justify-between lg:p-12">
        <Link to="/" className="flex items-center gap-2.5">
          <div className="flex h-8 w-8 items-center justify-center rounded-md bg-primary/15">
            <RailhookIcon className="h-4 w-4" />
          </div>
          <span className="text-[15px] font-semibold tracking-tight">Railhook</span>
        </Link>

        <div>
          <p className="mono-label mb-4 text-muted-foreground">{t('auth.panel.eyebrow')}</p>
          <div className="rounded-lg border border-rail bg-card p-5 font-mono text-[12px] leading-relaxed">
            {SAMPLE.map(([k, v]) => (
              <div key={k} className="flex gap-3">
                <span className="w-[9.5rem] flex-shrink-0 text-muted-foreground">{k}</span>
                <span className="truncate text-foreground">{v}</span>
              </div>
            ))}
            <div className="mt-5 border-t border-rail pt-4">
              <AttemptRail
                size="full"
                ariaLabel={t('auth.panel.railLabel')}
                attempts={[
                  { number: 1, outcome: 'failed', delayMinutes: 0, code: 503 },
                  { number: 2, outcome: 'failed', delayMinutes: 1, code: 503 },
                  { number: 3, outcome: 'failed', delayMinutes: 5, code: 502 },
                  { number: 4, outcome: 'ok', delayMinutes: 15, code: 200 },
                ]}
                maxAttempts={6}
              />
            </div>
          </div>
          <p className="mt-5 max-w-md text-sm text-muted-foreground">{t('auth.panel.caption')}</p>
        </div>

        <p className="text-xs text-muted-foreground">
          {t('auth.login.copyright', { year: new Date().getFullYear() })}
        </p>
      </aside>

      <div className="flex flex-1 flex-col bg-background px-6 py-8 lg:px-12">
        <div className="flex items-center justify-between">
          <Link
            to="/"
            className="inline-flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
          >
            <ArrowLeft className="h-4 w-4" />
            {t('common.backToHome')}
          </Link>
          <LanguageSwitcher />
        </div>

        <div className="flex flex-1 items-center justify-center py-10">
          <div className="animate-fade-in-up w-full max-w-[400px]">
            <div className="mb-7">
              <Link to="/" className="mb-6 flex items-center gap-2.5 lg:hidden">
                <div className="flex h-8 w-8 items-center justify-center rounded-md bg-primary">
                  <RailhookIcon className="h-4 w-4 text-primary-foreground" />
                </div>
                <span className="text-[15px] font-semibold">Railhook</span>
              </Link>
              <h1 className="text-title">{title}</h1>
              {subtitle && <p className="mt-1.5 text-sm text-muted-foreground">{subtitle}</p>}
            </div>

            {children}

            {footer && <div className="mt-7 text-center text-sm text-muted-foreground">{footer}</div>}
          </div>
        </div>
      </div>
    </div>
  );
}
