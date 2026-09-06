import { useState, useEffect } from 'react';
import { useSearchParams, Link, useNavigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { showApiError, showSuccess } from '../lib/toast';
import { http } from '../api/http';
import { useAuth } from './auth.store';
import { Button, buttonVariants } from '../components/ui/button';
import { cn } from '../lib/utils';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';

/**
 * Approving a CLI login is a security decision, so the code the person is
 * approving is the loudest thing on the screen and it is set in mono — it came
 * out of a terminal, and it has to be comparable character by character with
 * what is still on that terminal.
 */
function DeviceCode({ code }: { code: string }) {
  const { t } = useTranslation();
  return (
    <div className="rounded-lg border border-rail bg-card p-5 text-center">
      <p className="mono-label mb-3">{t('auth.device.codeLabel')}</p>
      <p className="font-mono text-[28px] font-semibold leading-none tracking-[0.2em] text-foreground">
        {code}
      </p>
    </div>
  );
}

export default function DeviceApprovePage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { isAuthenticated } = useAuth();

  const codeFromUrl = searchParams.get('code') || '';
  const [userCode, setUserCode] = useState(codeFromUrl);
  const [status, setStatus] = useState<
    'input' | 'confirming' | 'loading' | 'denying' | 'success' | 'denied' | 'error' | 'needsLogin'
  >('input');
  const [errorMessage, setErrorMessage] = useState('');

  useEffect(() => {
    if (!isAuthenticated) {
      setStatus('needsLogin');
    } else if (codeFromUrl) {
      setUserCode(codeFromUrl);
      setStatus('confirming');
    }
  }, [isAuthenticated, codeFromUrl]);

  const handleSubmitCode = (e: React.FormEvent) => {
    e.preventDefault();
    if (!userCode.trim()) return;
    setStatus('confirming');
  };

  const handleApprove = async () => {
    setStatus('loading');
    try {
      await http.post('/api/v1/auth/device/approve', { userCode: userCode.trim().toUpperCase() });
      setStatus('success');
      showSuccess(t('auth.device.approved'));
    } catch (err: any) {
      const msg = err.response?.data?.message || t('auth.device.failed');
      setErrorMessage(msg);
      setStatus('error');
      showApiError(err, 'auth.device.failed');
    }
  };

  /**
   * The other half of the decision. Cancel used to only clear the form, which told the terminal
   * nothing: the code stayed pending and whoever had asked for it kept polling until it expired.
   * Denying ends the request now, and the CLI stops on its next poll.
   */
  const handleDeny = async () => {
    setStatus('denying');
    try {
      await http.post('/api/v1/auth/device/deny', { userCode: userCode.trim().toUpperCase() });
      setStatus('denied');
      showSuccess(t('auth.device.denied'));
    } catch (err: any) {
      const msg = err.response?.data?.message || t('auth.device.denyFailed');
      setErrorMessage(msg);
      setStatus('error');
      showApiError(err, 'auth.device.denyFailed');
    }
  };

  const handleReset = () => {
    setUserCode('');
    setErrorMessage('');
    setStatus('input');
  };

  const formatCode = (code: string) => {
    const clean = code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
    if (clean.length > 4) {
      return clean.slice(0, 4) + '-' + clean.slice(4, 8);
    }
    return clean;
  };

  if (status === 'needsLogin') {
    return (
      <AuthLayout title={t('auth.device.loginRequired')} subtitle={t('auth.device.loginRequiredDesc')}>
        <Link
          to={`/login?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`}
          className={cn(buttonVariants(), 'h-10 w-full')}
        >
          {t('auth.device.goToLogin')}
        </Link>
      </AuthLayout>
    );
  }

  if (status === 'confirming') {
    return (
      <AuthLayout title={t('auth.device.confirmTitle')} subtitle={t('auth.device.confirmDesc')}>
        <div className="space-y-5">
          <DeviceCode code={formatCode(userCode)} />

          <div className="rounded-md border border-retry/25 bg-retry-soft p-3 text-sm text-retry">
            {t('auth.device.confirmWarning')}
          </div>

          <div className="space-y-2">
            <Button onClick={handleApprove} className="h-10 w-full">
              {t('auth.device.approve')}
            </Button>
            <Button variant="destructive" onClick={handleDeny} className="h-10 w-full">
              {t('auth.device.deny')}
            </Button>
            <Button variant="ghost" onClick={handleReset} className="h-10 w-full">
              {t('common.cancel')}
            </Button>
          </div>
        </div>
      </AuthLayout>
    );
  }

  if (status === 'loading' || status === 'denying') {
    const label = status === 'denying' ? t('auth.device.denying') : t('auth.device.approving');
    return (
      <AuthLayout title={label} subtitle={t('auth.device.confirmDesc')}>
        <div className="space-y-5">
          <DeviceCode code={formatCode(userCode)} />
          <div className="flex items-center gap-3 rounded-md border border-rail bg-card p-4 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden />
            {label}
          </div>
        </div>
      </AuthLayout>
    );
  }

  if (status === 'denied') {
    return (
      <AuthLayout title={t('auth.device.deniedTitle')} subtitle={t('auth.device.deniedDesc')}>
        <div className="space-y-5">
          <DeviceCode code={formatCode(userCode)} />
          <div className="rounded-md border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {t('auth.device.deniedHint')}
          </div>
          <Button className="h-10 w-full" onClick={() => navigate('/admin/dashboard')}>
            {t('auth.device.goToDashboard')}
          </Button>
        </div>
      </AuthLayout>
    );
  }

  if (status === 'success') {
    return (
      <AuthLayout title={t('auth.device.successTitle')} subtitle={t('auth.device.successDesc')}>
        <div className="space-y-5">
          <DeviceCode code={formatCode(userCode)} />
          <p className="text-sm text-muted-foreground">{t('auth.device.successHint')}</p>
          <Button className="h-10 w-full" onClick={() => navigate('/admin/dashboard')}>
            {t('auth.device.goToDashboard')}
          </Button>
        </div>
      </AuthLayout>
    );
  }

  if (status === 'error') {
    return (
      <AuthLayout title={t('auth.device.errorTitle')} subtitle={t('auth.device.failed')}>
        <div className="space-y-5">
          <div role="alert" className="rounded-md border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {errorMessage}
          </div>
          <Button className="h-10 w-full" onClick={handleReset}>
            {t('auth.device.tryAgain')}
          </Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={t('auth.device.title')} subtitle={t('auth.device.subtitle')}>
      <form onSubmit={handleSubmitCode} className="space-y-5">
        <div className="space-y-1.5">
          <Label htmlFor="userCode">{t('auth.device.codeLabel')}</Label>
          <Input
            id="userCode"
            type="text"
            placeholder="XXXX-XXXX"
            value={formatCode(userCode)}
            onChange={(e) => setUserCode(e.target.value.replace(/[^A-Za-z0-9-]/g, ''))}
            maxLength={9}
            className="text-center font-mono text-lg tracking-[0.2em]"
            autoFocus
            autoComplete="off"
          />
        </div>
        <Button
          type="submit"
          className="h-10 w-full"
          disabled={userCode.replace(/[^A-Za-z0-9]/g, '').length < 8}
        >
          {t('auth.device.continue')}
        </Button>
      </form>
    </AuthLayout>
  );
}
