import { useState } from 'react';
import { Link, useSearchParams, useNavigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { showApiError, showSuccess } from '../lib/toast';
import { authApi } from '../api/auth.api';
import { Button, buttonVariants } from '../components/ui/button';
import { cn } from '../lib/utils';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';

export default function ResetPasswordPage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const navigate = useNavigate();

  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (password !== confirmPassword) {
      setError(t('auth.resetPassword.mismatch'));
      return;
    }

    if (password.length < 8) {
      setError(t('auth.resetPassword.tooShort'));
      return;
    }

    setLoading(true);

    try {
      await authApi.resetPassword(token!, password);
      setSuccess(true);
      showSuccess(t('auth.resetPassword.successToast'));
    } catch (err: any) {
      const msg = err.response?.data?.message || t('auth.resetPassword.failed');
      setError(msg);
      showApiError(err, 'auth.resetPassword.failed');
    } finally {
      setLoading(false);
    }
  };

  if (!token) {
    return (
      <AuthLayout
        title={t('auth.resetPassword.invalidLink')}
        subtitle={t('auth.resetPassword.invalidLinkMessage')}
        footer={
          <Link to="/login" className="font-medium text-primary hover:underline">
            {t('auth.resetPassword.backToSignIn')}
          </Link>
        }
      >
        <Link to="/forgot-password" className={cn(buttonVariants(), 'h-10 w-full')}>
          {t('auth.resetPassword.requestNew')}
        </Link>
      </AuthLayout>
    );
  }

  if (success) {
    return (
      <AuthLayout
        title={t('auth.resetPassword.success')}
        subtitle={t('auth.resetPassword.successMessage')}
      >
        <Button className="h-10 w-full" onClick={() => navigate('/login')}>
          {t('auth.resetPassword.signIn')}
        </Button>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title={t('auth.resetPassword.title')}
      subtitle={t('auth.resetPassword.subtitle')}
      footer={
        <Link to="/login" className="font-medium text-primary hover:underline">
          {t('auth.resetPassword.backToSignIn')}
        </Link>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        <div className="space-y-1.5">
          <Label htmlFor="password">{t('auth.resetPassword.newPassword')}</Label>
          <Input
            id="password"
            type="password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            disabled={loading}
            autoComplete="new-password"
            autoFocus
          />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="confirmPassword">{t('auth.resetPassword.confirmPassword')}</Label>
          <Input
            id="confirmPassword"
            type="password"
            placeholder="••••••••"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            required
            disabled={loading}
            autoComplete="new-password"
          />
        </div>

        {error && (
          <div role="alert" className="animate-scale-in rounded-md border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {error}
          </div>
        )}

        <Button type="submit" className="h-10 w-full" disabled={loading}>
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
          {loading ? t('auth.resetPassword.submitting') : t('auth.resetPassword.submit')}
        </Button>
      </form>
    </AuthLayout>
  );
}
