import { useState } from 'react';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { authApi } from '../api/auth.api';
import type { ChangeEmailRequest, EmailChangeResponse } from '../types/api.types';
import GoogleSignInButton from '../auth/GoogleSignInButton';
import { hasImpossibleTld } from '../lib/emailTypos';
import CaptchaWidget, { isCaptchaConfigured } from './CaptchaWidget';
import EmailSuggestion from './EmailSuggestion';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';

/**
 * A new address for the signed-in account, asking for exactly what the API will: the CAPTCHA for
 * an unverified account, the password for a verified one, and a fresh Google sign-in for a verified
 * account that has no password.
 */
export default function ChangeEmailForm({
  unverified, hasPassword, currentEmail, initialValue = '', submitLabel, onDone, onCancel,
}: {
  unverified: boolean;
  hasPassword: boolean;
  currentEmail: string;
  initialValue?: string;
  submitLabel: string;
  onDone: (response: EmailChangeResponse) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [newEmail, setNewEmail] = useState(initialValue);
  const [password, setPassword] = useState('');
  const [captchaToken, setCaptchaToken] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const needsPassword = !unverified && hasPassword;
  const needsRecentSignIn = !unverified && !hasPassword;
  const trimmed = newEmail.trim();
  const unchanged = trimmed.toLowerCase() === currentEmail.toLowerCase();
  const canSubmit = Boolean(trimmed) && !unchanged && !hasImpossibleTld(trimmed)
    && (!needsPassword || Boolean(password))
    && (!unverified || !isCaptchaConfigured() || Boolean(captchaToken));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSaving(true);
    setError('');
    const request: ChangeEmailRequest = {
      newEmail: trimmed,
      ...(needsPassword ? { currentPassword: password } : {}),
      ...(unverified && captchaToken ? { captchaToken } : {}),
    };
    try {
      onDone(await authApi.requestEmailChange(request));
    } catch (err: any) {
      const data = err.response?.data;
      const fieldDetail = data?.fieldErrors
        ? Object.values(data.fieldErrors as Record<string, string>).join('. ')
        : '';
      setError(fieldDetail || data?.message || t('emailChange.failed'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="max-w-sm space-y-3">
      <div className="space-y-1.5">
        <Label htmlFor="email-change-new">{t('emailChange.newEmail')}</Label>
        <Input
          id="email-change-new"
          type="email"
          autoComplete="email"
          value={newEmail}
          onChange={(e) => { setNewEmail(e.target.value); setError(''); }}
          disabled={saving}
          autoFocus
        />
        <EmailSuggestion email={newEmail} onAccept={(s) => { setNewEmail(s); setError(''); }} />
      </div>

      {needsPassword && (
        <div className="space-y-1.5">
          <Label htmlFor="email-change-password">{t('emailChange.currentPassword')}</Label>
          <Input
            id="email-change-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={saving}
          />
        </div>
      )}

      {needsRecentSignIn && (
        <div className="space-y-2">
          <p className="text-xs text-muted-foreground">{t('emailChange.googleReauth')}</p>
          <GoogleSignInButton intent="login" returnTo="/admin/settings" />
        </div>
      )}

      {unverified && <CaptchaWidget onToken={setCaptchaToken} />}

      <p className="text-xs text-muted-foreground">
        {t(unverified ? 'emailChange.unverifiedHint' : 'emailChange.verifiedHint')}
      </p>

      {error && (
        <p role="alert" className="rounded-md border border-halt/25 bg-halt-soft p-2.5 text-sm text-halt">{error}</p>
      )}

      <div className="flex flex-wrap gap-2">
        <Button type="submit" size="sm" disabled={saving || !canSubmit}>
          {saving && <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />}
          {saving ? t('common.saving') : submitLabel}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onCancel} disabled={saving}>
          {t('common.cancel')}
        </Button>
      </div>
    </form>
  );
}
