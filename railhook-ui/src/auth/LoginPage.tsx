import { useState } from 'react';
import { useNavigate, Link, useLocation, useSearchParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import AuthLayout from './AuthLayout';
import GoogleSignInButton from './GoogleSignInButton';
import { useTranslation } from 'react-i18next';
import { showApiError, showError, showSuccess } from '../lib/toast';
import { authApi } from '../api/auth.api';
import { http } from '../api/http';
import { useLeaveDemo } from './useLeaveDemo';
import { useAuth } from './auth.store';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { destinationAfterSignIn, safeDestination } from '../lib/signInDestination';

const DEFAULT_DESTINATION = '/admin/projects';

export default function LoginPage() {
  const { t } = useTranslation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { login } = useAuth();
  useLeaveDemo();
  const [searchParams] = useSearchParams();
  // An invite or a CLI approval sends a signed-out visitor here with ?redirect=; a protected page
  // sends the path it was on as state.from.
  const redirect = searchParams.get('redirect');
  const from = (location.state as { from?: unknown } | null)?.from;
  const returnTo = redirect ? safeDestination(redirect, DEFAULT_DESTINATION) : undefined;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      const authResponse = await authApi.login({ email, password });
      http.setToken(authResponse.accessToken);
      const user = await authApi.getCurrentUser();
      login(authResponse.accessToken, user);
      showSuccess(t('auth.login.welcomeBack'));
      navigate(destinationAfterSignIn({ redirect, from, userId: user.user?.id }, DEFAULT_DESTINATION));
    } catch (err: any) {
      // A 403 with no message of ours is Spring refusing this page's Origin (CORS_ALLOWED_ORIGINS).
      // The generic toast for 403 says "no permission", which is wrong about a person who has not
      // signed in yet and says nothing an operator could fix.
      const originRejected = err.response?.status === 403 && typeof err.response?.data?.message !== 'string';
      const errorMessage = originRejected
        ? t('auth.login.originRejected')
        : err.response?.data?.message || t('auth.login.failed');
      setError(errorMessage);
      if (originRejected) {
        showError(errorMessage);
      } else {
        showApiError(err, 'auth.login.failed');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLayout
      title={t('auth.login.title')}
      subtitle={t('auth.login.subtitle')}
      footer={
        <>
          {t('auth.login.noAccount')}{' '}
          <Link to={returnTo ? `/register?redirect=${encodeURIComponent(returnTo)}` : '/register'} className="font-medium text-primary hover:underline">
            {t('auth.login.createAccount')}
          </Link>
        </>
      }
    >
      <GoogleSignInButton
        intent="login"
        returnTo={returnTo ?? (typeof from === 'string' ? safeDestination(from, DEFAULT_DESTINATION) : DEFAULT_DESTINATION)}
      />
      <form onSubmit={handleSubmit} className="space-y-5">
        <div className="space-y-1.5">
          <Label htmlFor="email">{t('auth.login.email')}</Label>
          <Input
            id="email"
            type="email"
            placeholder="name@company.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            disabled={loading}
            autoComplete="email"
          />
        </div>

        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <Label htmlFor="password">{t('auth.login.password')}</Label>
            <Link to="/forgot-password" className="text-xs text-primary hover:underline">
              {t('auth.login.forgotPassword')}
            </Link>
          </div>
          <Input
            id="password"
            type="password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            disabled={loading}
            autoComplete="current-password"
          />
        </div>

        {error && (
          <div role="alert" className="animate-scale-in rounded-md border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {error}
          </div>
        )}

        <Button type="submit" className="h-10 w-full" disabled={loading}>
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
          {loading ? t('auth.login.submitting') : t('auth.login.submit')}
        </Button>
      </form>
    </AuthLayout>
  );
}
