import { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { authApi } from '../api/auth.api';
import { useAuth } from '../auth/auth.store';
import { formatDateTime } from '../lib/date';
import { showApiError, showSuccess } from '../lib/toast';
import type { EmailChangeResponse } from '../types/api.types';
import ChangeEmailForm from './ChangeEmailForm';
import { Button } from './ui/button';

export default function EmailAddressSection() {
  const { t } = useTranslation();
  const { user, updateUser } = useAuth();
  const [state, setState] = useState<EmailChangeResponse | null>(null);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState<'resend' | 'cancel' | null>(null);

  useEffect(() => {
    authApi.getEmailChange().then(setState).catch(() => { /* the section still offers a change */ });
  }, []);

  if (!user) return null;
  const unverified = user.user.status === 'PENDING_VERIFICATION';
  const pending = state?.pendingEmail ? state : null;

  const handleDone = (response: EmailChangeResponse) => {
    setEditing(false);
    if (response.applied) {
      updateUser({ ...user, user: { ...user.user, email: response.email } });
      showSuccess(t('auth.verification.changed', { email: response.email }));
      return;
    }
    setState(response);
    showSuccess(t('emailChange.requested', { email: response.pendingEmail }));
  };

  const resend = async () => {
    setBusy('resend');
    try {
      setState(await authApi.resendEmailChange());
      showSuccess(t('emailChange.resent'));
    } catch (err) {
      showApiError(err, 'emailChange.failed');
    } finally {
      setBusy(null);
    }
  };

  const cancel = async () => {
    setBusy('cancel');
    try {
      await authApi.cancelEmailChange();
      setState((s) => (s ? { ...s, pendingEmail: undefined, pendingExpiresAt: undefined } : s));
      showSuccess(t('emailChange.cancelled'));
    } catch (err) {
      showApiError(err, 'emailChange.failed');
    } finally {
      setBusy(null);
    }
  };

  if (pending) {
    return (
      <div role="status" className="border border-retry/30 bg-retry-soft p-3 text-sm">
        <p>
          <Trans
            i18nKey="emailChange.pending"
            values={{ email: pending.pendingEmail }}
            components={{ strong: <strong className="font-mono text-[13px]" /> }}
          />
        </p>
        {pending.pendingExpiresAt && (
          <p className="mt-1 text-xs text-muted-foreground">
            {t('emailChange.pendingHint', { expires: formatDateTime(pending.pendingExpiresAt) })}
          </p>
        )}
        <div className="mt-3 flex flex-wrap gap-2">
          <Button size="sm" variant="outline" onClick={resend} disabled={busy !== null}>
            {busy === 'resend' && <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />}
            {t('emailChange.resend')}
          </Button>
          <Button size="sm" variant="ghost" onClick={cancel} disabled={busy !== null}>
            {busy === 'cancel' && <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />}
            {t('emailChange.cancelPending')}
          </Button>
        </div>
      </div>
    );
  }

  if (editing) {
    return (
      <ChangeEmailForm
        unverified={unverified}
        hasPassword={user.hasPassword !== false}
        currentEmail={user.user.email}
        submitLabel={t(unverified ? 'emailChange.submitUnverified' : 'emailChange.submit')}
        onDone={handleDone}
        onCancel={() => setEditing(false)}
      />
    );
  }

  return (
    <Button variant="outline" size="sm" className="w-fit" onClick={() => setEditing(true)}>
      {t('emailChange.change')}
    </Button>
  );
}
