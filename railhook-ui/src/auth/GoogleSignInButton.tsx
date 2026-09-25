import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { authApi } from '../api/auth.api';

interface Props {
  intent: 'login' | 'register';
  /** The API replaces anything that isn't a path on this site. */
  returnTo?: string;
}

const API_URL = import.meta.env.VITE_API_URL || '';

/** The API says whether it's configured, not the bundle; a plain link since the flow is full-page redirects. */
export default function GoogleSignInButton({ intent, returnTo }: Props) {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const [available, setAvailable] = useState(false);

  useEffect(() => {
    let cancelled = false;
    authApi
      .providers()
      .then((providers) => { if (!cancelled) setAvailable(Boolean(providers?.google)); })
      .catch(() => { if (!cancelled) setAvailable(false); });
    return () => { cancelled = true; };
  }, []);

  const error = searchParams.get('error');
  const errorMessage = error?.startsWith('google_')
    ? t(`auth.google.errors.${error}`, { defaultValue: t('auth.google.errors.generic') })
    : '';

  const params = new URLSearchParams({ intent });
  if (returnTo) params.set('returnTo', returnTo);

  return (
    <>
      {errorMessage && (
        <div role="alert" className="mb-5 border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
          {errorMessage}
        </div>
      )}
      {available && (
        <div className="mb-5 space-y-5">
          <a
            href={`${API_URL}/api/v1/auth/oauth/google/start?${params.toString()}`}
            className="flex h-10 w-full items-center justify-center gap-2.5 border border-rail bg-card text-sm font-medium text-foreground transition-colors hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <img src="/logos/brand/google.svg" alt="" aria-hidden="true" className="h-[18px] w-[18px]" />
            {t('auth.google.continue')}
          </a>
          <div className="flex items-center gap-3 text-xs text-muted-foreground">
            <span className="h-px flex-1 bg-rail" aria-hidden="true" />
            {t('auth.google.or')}
            <span className="h-px flex-1 bg-rail" aria-hidden="true" />
          </div>
        </div>
      )}
    </>
  );
}
