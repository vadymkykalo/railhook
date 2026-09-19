import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '../components/ui/button';
import CaptchaWidget, { isCaptchaConfigured } from '../components/CaptchaWidget';
import { useAuth } from '../auth/auth.store';
import { demoApi } from '../api/demo.api';
import { authApi } from '../api/auth.api';
import { http } from '../api/http';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { publicDemoEnabled } from '../lib/runtimeConfig';
import { resolveErrorMessage } from '../lib/toast';
import { Band, WRAP } from './landing/primitives';

const DASHBOARD = '/admin/dashboard';

/**
 * The way into the live demo: opens a read-only session in the demo organization and hands the
 * visitor to the dashboard. Straight through when the deployment asks no challenge; otherwise the
 * same CAPTCHA registration uses, then a button.
 */
export default function DemoPage() {
  const { t } = useTranslation();
  useDocumentMeta({ titleKey: 'meta.demo.title', descriptionKey: 'meta.demo.description', path: '/demo' });
  const navigate = useNavigate();
  const { user, startDemo } = useAuth();
  const enabled = publicDemoEnabled();
  const [captchaToken, setCaptchaToken] = useState('');
  const started = useRef(false);

  const open = useMutation({
    mutationFn: async () => {
      const session = await demoApi.createSession(captchaToken || undefined);
      // The dashboard's own "who am I" call, made with the demo token, so the session the app
      // holds is the one the server describes rather than one assembled here.
      const previous = http.getToken();
      const previousDemo = http.isDemo();
      http.setToken(session.accessToken);
      http.setDemo(true);
      try {
        const me = await authApi.getCurrentUser();
        return { session, me };
      } catch (err) {
        http.setToken(previous);
        http.setDemo(previousDemo);
        throw err;
      }
    },
    onSuccess: ({ session, me }) => {
      startDemo?.(session.accessToken, me, session.expiresAt);
      navigate(DASHBOARD, { replace: true });
    },
    // A challenge answer is single-use: a failed attempt needs a fresh one.
    onError: () => setCaptchaToken(''),
  });

  const alreadyInDemo = user?.demo === true;

  useEffect(() => {
    if (alreadyInDemo) {
      navigate(DASHBOARD, { replace: true });
      return;
    }
    // Without a challenge there is nothing to ask the visitor: open it at once. Once, although
    // React may run this effect twice in development.
    if (enabled && !isCaptchaConfigured() && !started.current) {
      started.current = true;
      open.mutate();
    }
  }, [enabled, alreadyInDemo]); // eslint-disable-line react-hooks/exhaustive-deps

  const needsChallenge = isCaptchaConfigured() && !captchaToken;

  return (
    <>
      <section className="pb-2 pt-14 sm:pt-20">
        <div className={WRAP}>
          <h1 className="max-w-3xl font-display text-[2.2rem] font-bold leading-[1.05] tracking-[-0.035em] text-foreground [text-wrap:balance] sm:text-[3.2rem]">
            {t('demo.page.title')}
          </h1>
          <p className="mt-4 max-w-2xl text-[1.1rem] text-muted-foreground">{t('demo.page.lead')}</p>
        </div>
      </section>

      <Band labelledBy="demo-open">
        <h2 id="demo-open" className="sr-only">{t('demo.page.title')}</h2>
        {!enabled ? (
          <div className="flex flex-col items-start gap-4 rounded-2xl border border-dashed border-rail p-6 sm:p-8">
            <p className="text-muted-foreground">{t('demo.page.disabled')}</p>
            <Button asChild variant="outline">
              <Link to="/">{t('demo.page.home')}</Link>
            </Button>
          </div>
        ) : (
          <div className="flex flex-col items-start gap-4 rounded-2xl border border-dashed border-rail p-6 sm:p-8">
            {open.isPending && (
              <p className="flex items-center gap-2 text-muted-foreground" role="status">
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                {t('demo.page.opening')}
              </p>
            )}
            {isCaptchaConfigured() && !open.isPending && (
              <>
                {/* Keyed on the failures so a failed attempt renders a fresh widget and a fresh answer. */}
                <CaptchaWidget key={open.failureCount} onToken={setCaptchaToken} />
                <Button onClick={() => open.mutate()} disabled={needsChallenge}>
                  {t('demo.page.start')}
                </Button>
              </>
            )}
            {open.isError && (
              <div className="flex flex-col items-start gap-3">
                <p role="alert" className="text-sm text-halt">
                  {resolveErrorMessage(open.error, 'demo.page.failed')}
                </p>
                {!isCaptchaConfigured() && (
                  <Button variant="outline" onClick={() => open.mutate()}>
                    {t('demo.page.retry')}
                  </Button>
                )}
              </div>
            )}
            <Link to="/register" className="text-sm text-primary underline-offset-4 hover:underline">
              {t('demo.page.signUp')}
            </Link>
          </div>
        )}
      </Band>
    </>
  );
}
