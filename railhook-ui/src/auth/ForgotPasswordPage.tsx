import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Loader2, Mail } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { showApiError } from '../lib/toast';
import { authApi } from '../api/auth.api';
import { Button, buttonVariants } from '../components/ui/button';
import { cn } from '../lib/utils';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';

export default function ForgotPasswordPage() {
  const { t } = useTranslation();
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      await authApi.forgotPassword(email);
      setSent(true);
    } catch (err: any) {
      if (err.response?.status === 429) {
        setError(t('auth.forgotPassword.tooMany'));
      } else {
        setError(err.response?.data?.message || t('auth.forgotPassword.genericError'));
      }
      showApiError(err, 'auth.forgotPassword.failedToast');
    } finally {
      setLoading(false);
    }
  };

  if (sent) {
    return (
      <AuthLayout
        title={t('auth.forgotPassword.checkEmail')}
        subtitle={<Trans i18nKey="auth.forgotPassword.sentMessage" values={{ email }} components={{ strong: <strong className="font-medium text-foreground" /> }} />}
      >
        <div className="space-y-3">
          <div className="flex items-center gap-3 rounded-md border border-rail bg-card p-3">
            <Mail className="h-4 w-4 flex-shrink-0 text-primary" aria-hidden />
            <span className="truncate font-mono text-[13px]">{email}</span>
          </div>
          <Button variant="outline" onClick={() => setSent(false)} className="h-10 w-full">
            {t('auth.forgotPassword.tryDifferent')}
          </Button>
          <Link to="/login" className={cn(buttonVariants({ variant: 'ghost' }), 'h-10 w-full')}>
            {t('auth.forgotPassword.backToSignIn')}
          </Link>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title={t('auth.forgotPassword.title')}
      subtitle={t('auth.forgotPassword.subtitle')}
      footer={
        <>
          {t('auth.forgotPassword.remember')}{' '}
          <Link to="/login" className="font-medium text-primary hover:underline">
            {t('auth.forgotPassword.signIn')}
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        <div className="space-y-1.5">
          <Label htmlFor="email">{t('auth.forgotPassword.email')}</Label>
          <Input
            id="email"
            type="email"
            placeholder="name@company.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            disabled={loading}
            autoComplete="email"
            autoFocus
          />
        </div>

        {error && (
          <div role="alert" className="animate-scale-in rounded-md border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {error}
          </div>
        )}

        <Button type="submit" className="h-10 w-full" disabled={loading}>
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
          {loading ? t('auth.forgotPassword.submitting') : t('auth.forgotPassword.submit')}
        </Button>
      </form>
    </AuthLayout>
  );
}
