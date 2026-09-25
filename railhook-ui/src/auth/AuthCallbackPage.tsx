import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { authApi } from '../api/auth.api';
import { http } from '../api/http';
import { useAuth } from './auth.store';
import { showSuccess } from '../lib/toast';
import { Button } from '../components/ui/button';
import { safeDestination } from '../lib/signInDestination';

/** The URL carries a one-time code, never a token. */
export default function AuthCallbackPage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useAuth();
  const [failed, setFailed] = useState(false);
  // The code works once; StrictMode's double effect would spend it and show a false error.
  const exchanged = useRef(false);

  useEffect(() => {
    if (exchanged.current) return;
    exchanged.current = true;

    const code = searchParams.get('code');
    const destination = safeDestination(searchParams.get('returnTo'));
    const isNewAccount = searchParams.get('new') === '1';
    if (!code) {
      setFailed(true);
      return;
    }

    (async () => {
      try {
        const authResponse = await authApi.exchangeSignInCode(code);
        http.setToken(authResponse.accessToken);
        const user = await authApi.getCurrentUser();
        login(authResponse.accessToken, user);
        if (isNewAccount) {
          showSuccess(t('auth.google.renameOrganization', { name: user.organization?.name }));
        }
        navigate(destination, { replace: true });
      } catch {
        setFailed(true);
      }
    })();
  }, [login, navigate, searchParams, t]);

  if (failed) {
    return (
      <AuthLayout title={t('auth.google.failedTitle')} subtitle={t('auth.google.errors.google_exchange')}>
        <Button asChild className="h-10 w-full">
          <Link to="/login">{t('auth.google.backToSignIn')}</Link>
        </Button>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={t('auth.google.signingIn')}>
      <div className="flex justify-center py-6" role="status" aria-label={t('auth.google.signingIn')}>
        <Loader2 className="h-6 w-6 animate-spin text-primary" aria-hidden="true" />
      </div>
    </AuthLayout>
  );
}
