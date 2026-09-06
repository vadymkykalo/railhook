import { useState } from 'react';
import CaptchaWidget, { isCaptchaConfigured } from '../components/CaptchaWidget';
import { useNavigate, Link } from 'react-router-dom';
import { Loader2, Mail } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { showApiError, showSuccess } from '../lib/toast';
import { authApi } from '../api/auth.api';
import { http } from '../api/http';
import { useAuth } from './auth.store';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import IntentPicker from '../components/IntentPicker';
import { writeIntent } from '../lib/onboarding';
import PasswordStrengthIndicator, { passwordMeetsPolicy } from '../components/PasswordStrengthIndicator';

export default function RegisterPage() {
  const { t } = useTranslation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [organizationName, setOrganizationName] = useState('');
  const [captchaToken, setCaptchaToken] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [registered, setRegistered] = useState(false);
  const [showIntent, setShowIntent] = useState(false);
  const [resending, setResending] = useState(false);
  const navigate = useNavigate();
  const { login } = useAuth();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      const authResponse = await authApi.register({
        email,
        password,
        fullName,
        organizationName,
        // Absent unless the deployment configured a challenge; the API accepts a registration
        // without one in exactly that case.
        ...(captchaToken ? { captchaToken } : {}),
      });
      http.setToken(authResponse.accessToken);
      const user = await authApi.getCurrentUser();
      login(authResponse.accessToken, user);
      showSuccess(t('auth.register.success'));
      setRegistered(true);
    } catch (err: any) {
      // The API answers a rejected field with fieldErrors {field: reason} and a
      // generic "Invalid request parameters" summary. Showing the summary threw
      // away the only part that says what to change.
      const data = err.response?.data;
      const fieldDetail = data?.fieldErrors
        ? Object.values(data.fieldErrors as Record<string, string>).join('. ')
        : '';
      const errorMessage = fieldDetail || data?.message || t('auth.register.failed');
      setError(errorMessage);
      showApiError(err, 'auth.register.failed');
    } finally {
      setLoading(false);
    }
  };

  const handleResend = async () => {
    setResending(true);
    try {
      await authApi.resendVerification(email);
      showSuccess(t('auth.verification.sent'));
    } catch (err: any) {
      showApiError(err, 'auth.verification.failed');
    } finally {
      setResending(false);
    }
  };

  if (registered && showIntent) {
    return (
      <AuthLayout title={t('auth.intent.title')} subtitle={t('auth.intent.subtitle')}>
        <IntentPicker
          onSelect={(intent) => {
            writeIntent(intent);
            navigate('/admin/dashboard');
          }}
        />
      </AuthLayout>
    );
  }

  if (registered) {
    return (
      <AuthLayout
        title={t('auth.register.checkEmail')}
        subtitle={<Trans i18nKey="auth.register.verificationSent" values={{ email }} components={{ strong: <strong className="font-medium text-foreground" /> }} />}
      >
        <div className="space-y-3">
          <div className="flex items-center gap-3 rounded-md border border-rail bg-card p-3">
            <Mail className="h-4 w-4 flex-shrink-0 text-primary" aria-hidden />
            <span className="truncate font-mono text-[13px]">{email}</span>
          </div>
          <Button onClick={() => setShowIntent(true)} className="h-10 w-full">
            {t('auth.register.continueToDashboard')}
          </Button>
          <Button variant="outline" onClick={handleResend} disabled={resending} className="h-10 w-full">
            {resending && <Loader2 className="h-4 w-4 animate-spin" />}
            {resending ? t('common.sending') : t('auth.register.resendVerification')}
          </Button>
          <p className="text-xs text-muted-foreground">{t('auth.register.resendHint')}</p>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title={t('auth.register.title')}
      subtitle={t('auth.register.subtitle')}
      footer={
        <>
          {t('auth.register.hasAccount')}{' '}
          <Link to="/login" className="font-medium text-primary hover:underline">
            {t('auth.register.signIn')}
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="fullName">{t('auth.register.fullName')}</Label>
            <Input
              id="fullName"
              type="text"
              placeholder={t('auth.register.fullNamePlaceholder')}
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              required
              disabled={loading}
              autoComplete="name"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="organizationName">{t('auth.register.organization')}</Label>
            <Input
              id="organizationName"
              type="text"
              placeholder={t('auth.register.organizationPlaceholder')}
              value={organizationName}
              onChange={(e) => setOrganizationName(e.target.value)}
              required
              disabled={loading}
              autoComplete="organization"
            />
          </div>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="email">{t('auth.register.email')}</Label>
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
          <Label htmlFor="password">{t('auth.register.password')}</Label>
          <Input
            id="password"
            type="password"
            placeholder={t('auth.register.passwordPlaceholder')}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            disabled={loading}
            autoComplete="new-password"
          />
          <PasswordStrengthIndicator password={password} />
        </div>

        {error && (
          <div role="alert" className="animate-scale-in rounded-md border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
            {error}
          </div>
        )}

        <CaptchaWidget onToken={setCaptchaToken} />

        <Button
          type="submit"
          className="h-10 w-full"
          disabled={loading || !passwordMeetsPolicy(password)
            || (isCaptchaConfigured() && !captchaToken)}
        >
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
          {loading ? t('auth.register.submitting') : t('auth.register.submit')}
        </Button>

        <p className="text-xs text-muted-foreground">{t('auth.register.terms')}</p>
      </form>
    </AuthLayout>
  );
}
