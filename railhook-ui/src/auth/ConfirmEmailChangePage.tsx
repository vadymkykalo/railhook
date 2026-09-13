import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { authApi } from '../api/auth.api';
import { Button } from '../components/ui/button';
import { useAuth } from './auth.store';

/**
 * Opened from the link mailed to the new address. Confirming signs every session out, this tab's
 * included, so the only way on is to sign in with the new address.
 */
export default function ConfirmEmailChangePage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { logout } = useAuth();
  const token = searchParams.get('token');
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading');
  const [errorMessage, setErrorMessage] = useState('');
  // The token is single-use; a second run of the effect must not spend it again.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    if (!token) {
      setStatus('error');
      setErrorMessage(t('emailChange.links.noToken'));
      return;
    }
    authApi.confirmEmailChange(token)
      .then(() => {
        logout();
        setStatus('success');
      })
      .catch((err: any) => {
        setStatus('error');
        setErrorMessage(err.response?.data?.message || t('emailChange.links.confirmFailed'));
      });
  }, [token, t, logout]);

  if (status === 'loading') {
    return (
      <AuthLayout title={t('emailChange.links.confirming')} subtitle={t('verifyEmail.pleaseWait')}>
        <div className="flex items-center gap-3 rounded-md border border-rail bg-card p-4 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden />
          {t('emailChange.links.confirming')}
        </div>
      </AuthLayout>
    );
  }

  if (status === 'error') {
    return (
      <AuthLayout title={t('emailChange.links.confirmErrorTitle')} subtitle={t('emailChange.links.confirmFailed')}>
        <div className="space-y-5">
          <div role="alert" className="rounded-md border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {errorMessage}
          </div>
          <Button className="h-10 w-full" onClick={() => navigate('/login')}>{t('verifyEmail.goToLogin')}</Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={t('emailChange.links.confirmedTitle')} subtitle={t('emailChange.links.confirmedDesc')}>
      <Button className="h-10 w-full" onClick={() => navigate('/login')}>{t('verifyEmail.goToLogin')}</Button>
    </AuthLayout>
  );
}
