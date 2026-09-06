import { useState, useEffect } from 'react';
import { useSearchParams, useNavigate, Link } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { membersApi } from '../api/members.api';
import { Button, buttonVariants } from '../components/ui/button';
import { cn } from '../lib/utils';
import { useAuth } from './auth.store';

/**
 * An outcome screen: the invite token in the URL has already decided what
 * happens. Each state names the outcome and offers exactly one way on — the
 * "create an account instead" path is a cross-link, not a second button
 * competing with it.
 */
export default function AcceptInvitePage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { isAuthenticated } = useAuth();
  const token = searchParams.get('token');
  const orgId = searchParams.get('orgId');

  const [status, setStatus] = useState<'loading' | 'success' | 'error' | 'needsLogin'>('loading');
  const [errorMessage, setErrorMessage] = useState('');

  useEffect(() => {
    if (!token || !orgId) {
      setStatus('error');
      setErrorMessage(t('invite.noToken'));
      return;
    }

    if (!isAuthenticated) {
      setStatus('needsLogin');
      return;
    }

    membersApi.acceptInvite(orgId, token)
      .then(() => setStatus('success'))
      .catch((err: any) => {
        setStatus('error');
        setErrorMessage(err.response?.data?.message || t('invite.failed'));
      });
  }, [token, orgId, isAuthenticated, t]);

  const returnTo = encodeURIComponent(window.location.pathname + window.location.search);

  if (status === 'loading') {
    return (
      <AuthLayout title={t('invite.accepting')} subtitle={t('invite.pleaseWait')}>
        <div className="flex items-center gap-3 rounded-md border border-rail bg-card p-4 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden />
          {t('invite.accepting')}
        </div>
      </AuthLayout>
    );
  }

  if (status === 'needsLogin') {
    return (
      <AuthLayout
        title={t('invite.loginRequired')}
        subtitle={t('invite.loginRequiredDesc')}
        footer={
          <>
            {t('invite.newUserHint')}{' '}
            <Link to={`/register?redirect=${returnTo}`} className="font-medium text-primary hover:underline">
              {t('invite.goToRegister')}
            </Link>
          </>
        }
      >
        <Link to={`/login?redirect=${returnTo}`} className={cn(buttonVariants(), 'h-10 w-full')}>
          {t('invite.goToLogin')}
        </Link>
      </AuthLayout>
    );
  }

  if (status === 'error') {
    return (
      <AuthLayout title={t('invite.errorTitle')} subtitle={t('invite.failed')}>
        <div className="space-y-5">
          <div role="alert" className="rounded-md border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {errorMessage}
          </div>
          <Button className="h-10 w-full" onClick={() => navigate('/login')}>
            {t('invite.goToLogin')}
          </Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={t('invite.success')} subtitle={t('invite.successDesc')}>
      <Button className="h-10 w-full" onClick={() => navigate('/admin/dashboard')}>
        {t('invite.goToDashboard')}
      </Button>
    </AuthLayout>
  );
}
