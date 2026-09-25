import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { authApi } from '../api/auth.api';
import { Button } from '../components/ui/button';
import { useAuth } from './auth.store';

/** Cancelling signs every session out, so a new password is the only next step. */
export default function CancelEmailChangePage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { logout } = useAuth();
  const token = searchParams.get('token');
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    if (!token) {
      setStatus('error');
      setErrorMessage(t('emailChange.links.noToken'));
      return;
    }
    authApi.cancelEmailChangeByToken(token)
      .then(() => {
        logout();
        setStatus('success');
      })
      .catch((err: any) => {
        setStatus('error');
        setErrorMessage(err.response?.data?.message || t('emailChange.links.cancelFailed'));
      });
  }, [token, t, logout]);

  if (status === 'loading') {
    return (
      <AuthLayout title={t('emailChange.links.cancelling')} subtitle={t('verifyEmail.pleaseWait')}>
        <div className="flex items-center gap-3 border border-rail bg-card p-4 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden />
          {t('emailChange.links.cancelling')}
        </div>
      </AuthLayout>
    );
  }

  if (status === 'error') {
    return (
      <AuthLayout title={t('emailChange.links.cancelErrorTitle')} subtitle={t('emailChange.links.cancelFailed')}>
        <div className="space-y-5">
          <div role="alert" className="border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {errorMessage}
          </div>
          <Button className="h-10 w-full" onClick={() => navigate('/login')}>{t('verifyEmail.goToLogin')}</Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={t('emailChange.links.cancelledTitle')} subtitle={t('emailChange.links.cancelledDesc')}>
      <Button asChild className="h-10 w-full">
        <Link to="/forgot-password">{t('emailChange.links.resetPassword')}</Link>
      </Button>
    </AuthLayout>
  );
}
