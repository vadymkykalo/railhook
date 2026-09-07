import { useState, useEffect, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, Check, Loader2, ShieldCheck } from 'lucide-react';
import { useAuth } from '../auth/auth.store';
import { usePermissions } from '../auth/usePermissions';
import { authApi } from '../api/auth.api';
import { showApiError, showSuccess } from '../lib/toast';
import { getStoredTimezone, setStoredTimezone } from '../lib/date';
import { forgetIntent, isAnyDismissed, setAllDismissed } from '../lib/onboarding';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import StatusBadge from '../components/StatusBadge';
import { RoleCard } from '../components/PermissionGate';
import PasswordStrengthIndicator from '../components/PasswordStrengthIndicator';
import ActiveSessions from '../components/ActiveSessions';
import DangerConfirmDialog from '../components/DangerConfirmDialog';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import { Switch } from '../components/ui/switch';

/**
 * The profile form. Each section answers one question and carries its own save
 * control in the same place, because the page it replaced was a single stack of
 * inputs with save buttons wherever they happened to fit.
 */

const COMMON_TIMEZONES = [
  'UTC', 'America/New_York', 'America/Chicago', 'America/Denver', 'America/Los_Angeles',
  'America/Sao_Paulo', 'Europe/London', 'Europe/Paris', 'Europe/Berlin', 'Europe/Kyiv',
  'Asia/Dubai', 'Asia/Kolkata', 'Asia/Shanghai', 'Asia/Tokyo', 'Asia/Seoul',
  'Australia/Sydney', 'Pacific/Auckland',
];

/**
 * One titled section of a settings form: what it is on the left, the fields on
 * the right, and — when the section can be saved — its control on the same
 * baseline every other section uses.
 */
export function FormSection({
  title, description, children, footer,
}: {
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <section className="border-t border-rail pt-8 first:border-t-0 first:pt-0">
      <div className="grid gap-4 lg:grid-cols-[minmax(0,14rem)_minmax(0,1fr)] lg:gap-10">
        <div>
          <h3 className="text-[15px] font-medium">{title}</h3>
          {description && <p className="mt-1 text-sm leading-snug text-muted-foreground">{description}</p>}
        </div>
        <div className="min-w-0 space-y-4">
          {children}
          {footer && (
            <div className="flex flex-wrap items-center justify-end gap-3 border-t border-rail pt-4">{footer}</div>
          )}
        </div>
      </div>
    </section>
  );
}

/** The save control, in the same place in every section, with what it did. */
export function SaveControl({
  label, savingLabel, saving, disabled, saved, onClick, type = 'button',
}: {
  label: string;
  savingLabel: string;
  saving: boolean;
  disabled?: boolean;
  /** True once a save landed and nothing has changed since. */
  saved?: boolean;
  onClick?: () => void;
  type?: 'button' | 'submit';
}) {
  const { t } = useTranslation();
  return (
    <>
      {saved && !saving && (
        <span className="flex items-center gap-1.5 text-[13px] text-ok" role="status">
          <Check className="h-3.5 w-3.5" aria-hidden />
          {t('settings.saved')}
        </span>
      )}
      <Button type={type} onClick={onClick} disabled={disabled || saving} size="sm">
        {saving && <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />}
        {saving ? savingLabel : label}
      </Button>
    </>
  );
}

/** A read-only fact about the signed-in person: the product said it, so it is mono. */
function FactRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b border-rail py-2.5 last:border-b-0">
      <span className="mono-label w-40 flex-shrink-0">{label}</span>
      <span className="min-w-0 break-words text-sm">{children}</span>
    </div>
  );
}

export default function SettingsPage() {
  const { t } = useTranslation();
  const { user, updateUser } = useAuth();
  const { role } = usePermissions();

  const [fullName, setFullName] = useState(user?.user?.fullName || '');
  const [savingProfile, setSavingProfile] = useState(false);
  const [profileSaved, setProfileSaved] = useState(false);
  const profileDirty = fullName !== (user?.user?.fullName || '');

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [changingPassword, setChangingPassword] = useState(false);
  const [passwordError, setPasswordError] = useState('');
  const [passwordSaved, setPasswordSaved] = useState(false);

  const [selectedTz, setSelectedTz] = useState(getStoredTimezone);
  const [showGettingStarted, setShowGettingStarted] = useState(() => !isAnyDismissed());

  useEffect(() => {
    setFullName(user?.user?.fullName || '');
  }, [user?.user?.fullName]);

  const handleSaveProfile = async () => {
    setSavingProfile(true);
    try {
      const updated = await authApi.updateProfile({ fullName: fullName.trim() || undefined });
      if (user) {
        updateUser({ ...user, user: { ...user.user, fullName: updated.fullName } });
      }
      setProfileSaved(true);
      showSuccess(t('settings.toast.profileUpdated'));
    } catch (err: any) {
      showApiError(err, 'settings.profileUpdateFailed');
    } finally {
      setSavingProfile(false);
    }
  };

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    setPasswordError('');
    setPasswordSaved(false);

    if (newPassword !== confirmPassword) {
      setPasswordError(t('settings.passwordMismatch'));
      return;
    }
    if (newPassword.length < 8) {
      setPasswordError(t('settings.passwordTooShort'));
      return;
    }

    setChangingPassword(true);
    try {
      await authApi.changePassword(currentPassword, newPassword);
      setPasswordSaved(true);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      showSuccess(t('settings.toast.passwordChanged'));
    } catch (err: any) {
      const msg = err.response?.data?.message || t('settings.passwordChangeFailed');
      setPasswordError(msg);
      showApiError(err, 'settings.passwordChangeFailed');
    } finally {
      setChangingPassword(false);
    }
  };

  const verified = user?.user?.status !== 'PENDING_VERIFICATION';

  // Everything below reads out of the auth store. Until the session restore in
  // App.tsx lands, that store is empty and the form would render as blank
  // fields with a blank email beside them. There is no error branch to pair
  // with this: a restore that fails clears the session and routes to login, so
  // this page never sees a failure it could report.
  if (!user) {
    return (
      <PageSkeleton maxWidth="max-w-4xl">
        <SkeletonCards count={3} height="h-44" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  return (
    <div className="p-4 lg:p-6">
      <div className="max-w-4xl">
        <PageHeader
          eyebrow={user?.organization?.name}
          title={t('settings.title')}
          description={t('settings.subtitle')}
        />

        <div className="space-y-8">
          <FormSection
            title={t('settings.profile')}
            description={t('settings.profileDesc')}
            footer={
              <SaveControl
                label={t('common.save')}
                savingLabel={t('common.saving')}
                saving={savingProfile}
                disabled={!profileDirty}
                saved={profileSaved && !profileDirty}
                onClick={handleSaveProfile}
              />
            }
          >
            <div className="space-y-2">
              <Label htmlFor="fullName">{t('settings.fullName')}</Label>
              <Input
                id="fullName"
                value={fullName}
                onChange={(e) => { setFullName(e.target.value); setProfileSaved(false); }}
                placeholder={t('settings.fullNamePlaceholder')}
                disabled={savingProfile}
                className="max-w-sm"
              />
              <p className="text-xs text-muted-foreground">{t('settings.fullNameHint')}</p>
            </div>

            <div className="pt-1">
              <FactRow label={t('settings.email')}>
                <span className="font-mono text-[13px]">{user?.user?.email}</span>
              </FactRow>
              <FactRow label={t('settings.emailStatus')}>
                <StatusBadge
                  kind={verified ? 'ok' : 'retry'}
                  label={t(verified ? 'settings.emailVerified' : 'settings.emailUnverified')}
                />
              </FactRow>
              <FactRow label={t('settings.organization')}>
                {user?.organization?.name}
              </FactRow>
            </div>
          </FormSection>

          <FormSection
            title={t('settings.yourAccess')}
            description={t('settings.yourAccessDesc')}
          >
            <div className="max-w-md">
              <RoleCard role={role} />
            </div>
          </FormSection>

          <form onSubmit={handleChangePassword}>
            <FormSection
              title={t('settings.changePassword')}
              description={t('settings.changePasswordDesc')}
              footer={
                <SaveControl
                  type="submit"
                  label={t('settings.changePasswordBtn')}
                  savingLabel={t('settings.changingPassword')}
                  saving={changingPassword}
                  disabled={!currentPassword || !newPassword || !confirmPassword}
                  saved={passwordSaved}
                />
              }
            >
              <div className="space-y-2">
                <Label htmlFor="currentPassword">{t('settings.currentPassword')}</Label>
                <Input
                  id="currentPassword"
                  type="password"
                  autoComplete="current-password"
                  value={currentPassword}
                  onChange={(e) => { setCurrentPassword(e.target.value); setPasswordSaved(false); }}
                  required
                  disabled={changingPassword}
                  className="max-w-sm"
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="newPassword">{t('settings.newPassword')}</Label>
                <Input
                  id="newPassword"
                  type="password"
                  autoComplete="new-password"
                  value={newPassword}
                  onChange={(e) => { setNewPassword(e.target.value); setPasswordSaved(false); }}
                  required
                  disabled={changingPassword}
                  className="max-w-sm"
                />
                <div className="max-w-sm">
                  <PasswordStrengthIndicator password={newPassword} />
                </div>
              </div>
              <div className="space-y-2">
                <Label htmlFor="confirmPassword">{t('settings.confirmPassword')}</Label>
                <Input
                  id="confirmPassword"
                  type="password"
                  autoComplete="new-password"
                  value={confirmPassword}
                  onChange={(e) => { setConfirmPassword(e.target.value); setPasswordSaved(false); }}
                  required
                  disabled={changingPassword}
                  className="max-w-sm"
                />
              </div>
              {passwordError && (
                <p role="alert" className="max-w-sm rounded-md border border-halt/30 bg-halt-soft px-3 py-2 text-sm text-halt">
                  {passwordError}
                </p>
              )}
            </FormSection>
          </form>

          <FormSection
            title={t('settings.sessions.title')}
            description={t('settings.sessions.description')}
          >
            <ActiveSessions />
          </FormSection>

          <FormSection
            title={t('settings.timezone.title')}
            description={t('settings.timezone.description')}
          >
            <div className="space-y-2">
              <Label htmlFor="timezone">{t('settings.timezone.select')}</Label>
              <Select
                id="timezone"
                value={selectedTz}
                onChange={(e) => {
                  const tz = e.target.value;
                  setSelectedTz(tz);
                  setStoredTimezone(tz);
                  showSuccess(t('settings.timezone.saved'));
                }}
                className="max-w-sm font-mono text-[13px]"
              >
                {!COMMON_TIMEZONES.includes(selectedTz) && (
                  <option value={selectedTz}>{selectedTz}</option>
                )}
                {COMMON_TIMEZONES.map((tz) => (
                  <option key={tz} value={tz}>{tz.replace(/_/g, ' ')}</option>
                ))}
              </Select>
              <p className="text-xs text-muted-foreground">{t('settings.timezone.hint', { tz: selectedTz })}</p>
            </div>
          </FormSection>

          <FormSection
            title={t('settings.gettingStarted.title')}
            description={t('settings.gettingStarted.description')}
          >
            <div className="divide-y divide-rail">
              <label className="flex cursor-pointer items-center justify-between gap-4 py-3 first:pt-0">
                <span className="min-w-0">
                  <span className="block text-sm font-medium">{t('settings.gettingStarted.show')}</span>
                  <span className="block text-xs text-muted-foreground">{t('settings.gettingStarted.showHint')}</span>
                </span>
                <Switch
                  checked={showGettingStarted}
                  onCheckedChange={(checked) => {
                    setShowGettingStarted(checked);
                    setAllDismissed(!checked);
                  }}
                  aria-label={t('settings.gettingStarted.show')}
                />
              </label>
              <div className="flex items-center justify-between gap-4 py-3 last:pb-0">
                <span className="min-w-0">
                  <span className="block text-sm font-medium">{t('settings.gettingStarted.askAgain')}</span>
                  <span className="block text-xs text-muted-foreground">{t('settings.gettingStarted.askAgainHint')}</span>
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    forgetIntent();
                    showSuccess(t('settings.gettingStarted.asked'));
                  }}
                >
                  {t('settings.gettingStarted.askAgain')}
                </Button>
              </div>
            </div>
            <p className="text-xs text-muted-foreground">{t('settings.deviceOnly')}</p>
          </FormSection>

          <FormSection
            title={t('settings.security.title')}
            description={t('settings.security.subtitle')}
          >
            <ul className="space-y-2.5">
              {(['tip1', 'tip2', 'tip3', 'tip4'] as const).map((tip) => (
                <li key={tip} className="flex gap-2.5 text-sm">
                  <ShieldCheck className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary" aria-hidden />
                  <span>{t(`settings.security.${tip}`)}</span>
                </li>
              ))}
            </ul>
          </FormSection>

          <EraseAccount />
        </div>
      </div>
    </div>
  );
}

/**
 * The right to erasure, where the person exercising it can reach it. The API had it before this
 * did, which meant the answer to "delete my account" was "write to support" — and a right you
 * have to ask a human for is one most people never exercise.
 *
 * <p>Confirmation runs through DangerConfirmDialog like every other irreversible action here, so
 * the ritual is the one the user has already seen. The 409 — last owner of an organization other
 * people still belong to — is shown as the backend words it, because it names the thing they
 * have to do first.
 */
function EraseAccount() {
  const { t } = useTranslation();
  const { user, logout } = useAuth();
  const [confirming, setConfirming] = useState(false);
  const [erasing, setErasing] = useState(false);

  const email = user?.user?.email ?? '';

  const handleErase = async () => {
    setErasing(true);
    try {
      await authApi.eraseOwnAccount();
      showSuccess(t('settings.erase.success'));
      logout();
    } catch (err: unknown) {
      showApiError(err, 'settings.erase.failed');
    } finally {
      setErasing(false);
    }
  };

  return (
    <section className="rounded-xl border border-halt/30 bg-halt-soft/50 p-5">
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-4 w-4 text-halt" aria-hidden />
        <h3 className="text-[15px] font-medium text-halt">{t('settings.erase.title')}</h3>
      </div>
      <div className="mt-4 flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 max-w-lg">
          <p className="text-sm font-medium">{t('settings.erase.action')}</p>
          <p className="mt-1 text-sm text-muted-foreground">{t('settings.erase.description')}</p>
        </div>
        <Button variant="destructive" size="sm" onClick={() => setConfirming(true)}>
          {t('settings.erase.action')}
        </Button>
      </div>

      <DangerConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t('settings.erase.action')}
        description={t('settings.erase.description')}
        confirmName={email}
        impact={[
          t('settings.erase.impactIdentity'),
          t('settings.erase.impactSessions'),
          t('settings.erase.impactSoleOrgs'),
          t('settings.erase.impactIrreversible'),
        ]}
        onConfirm={handleErase}
        loading={erasing}
        confirmLabel={t('settings.erase.confirmButton')}
      />
    </section>
  );
}
