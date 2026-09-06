import { useState, useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { authApi } from '../api/auth.api';
import { Button } from '../components/ui/button';
import { useAuth } from './auth.store';

/**
 * An outcome screen, not a form: the token in the URL has already decided what
 * happens. Each state says plainly what happened and offers exactly one way on.
 */
export default function VerifyEmailPage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { isAuthenticated } = useAuth();
  const token = searchParams.get('token');

  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading');
  const [errorMessage, setErrorMessage] = useState('');

  useEffect(() => {
    if (!token) {
      setStatus('error');
      setErrorMessage(t('verifyEmail.noToken'));
      return;
    }

    authApi.verifyEmail(token)
      .then(() => {
        setStatus('success');
      })
      .catch((err: any) => {
        setStatus('error');
        setErrorMessage(err.response?.data?.message || t('verifyEmail.failed'));
      });
  }, [token, t]);

  if (status === 'loading') {
    return (
      <AuthLayout title={t('verifyEmail.verifying')} subtitle={t('verifyEmail.pleaseWait')}>
        <div className="flex items-center gap-3 rounded-md border border-rail bg-card p-4 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden />
          {t('verifyEmail.verifying')}
        </div>
      </AuthLayout>
    );
  }

  if (status === 'error') {
    return (
      <AuthLayout title={t('verifyEmail.errorTitle')} subtitle={t('verifyEmail.failed')}>
        <div className="space-y-5">
          <div role="alert" className="rounded-md border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {errorMessage}
          </div>
          <Button className="h-10 w-full" onClick={() => navigate('/login')}>
            {t('verifyEmail.goToLogin')}
          </Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={t('verifyEmail.success')} subtitle={t('verifyEmail.successDesc')}>
      <Button
        className="h-10 w-full"
        onClick={() => navigate(isAuthenticated ? '/admin/dashboard' : '/login')}
      >
        {isAuthenticated ? t('verifyEmail.goToDashboard') : t('verifyEmail.goToLogin')}
      </Button>
    </AuthLayout>
  );
}
