import { useEffect, useId, useState, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Loader2, LogIn, ShieldCheck } from 'lucide-react';
import { useAuth } from '../auth/auth.store';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { useReinstateOrganization, useSuspendOrganization } from '../api/queries';
import {
  isReauthenticationRequired,
  type AdminOrganization,
  type AdminUserStatus,
  type SignInMethod,
} from '../api/platformAdmin.api';
import { docsUrl } from '../lib/docsUrl';
import { formatNumber } from '../lib/date';
import { showApiError, showSuccess } from '../lib/toast';
import { cn } from '../lib/utils';

/**
 * The pieces the platform admin pages share: how an organization's and an account's standing is
 * shown, how sign-in methods read, and the two dialogs and states every view needs.
 */

/** The reason field's limit, matching SuspendOrganizationRequest on the server. */
export const REASON_MAX = 500;

/** A search box that asks the server once the person stops typing, not on every key. */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}

/**
 * A failed load. The sign-in-too-old refusal gets its own state, because what fixes it is signing
 * in again, and a generic "could not load" with a retry button would loop on the same 403.
 */
export function PlatformErrorState({
  error, onRetry, retrying,
}: {
  error: unknown;
  onRetry?: () => void;
  retrying?: boolean;
}) {
  const { t } = useTranslation();
  const { logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  if (isReauthenticationRequired(error)) {
    return (
      <EmptyState
        icon={LogIn}
        title={t('platformAdmin.reauth.title')}
        description={t('platformAdmin.reauth.description')}
        action={(
          <Button
            onClick={() => {
              logout();
              navigate('/login', { state: { from: location.pathname } });
            }}
          >
            <LogIn className="h-4 w-4" aria-hidden /> {t('platformAdmin.reauth.action')}
          </Button>
        )}
      />
    );
  }
  return <ErrorState error={error} fallbackKey="platformAdmin.loadFailed" onRetry={onRetry} retrying={retrying} />;
}

/**
 * Who sees the panel and what it can change, said once at the top of each view. The wording
 * follows the code: the rule PlatformAdminAccessService applies, and suspend/reinstate being the
 * only writes the admin API has.
 */
export function PlatformScope() {
  const { t, i18n } = useTranslation();
  return (
    <p className="mb-5 flex max-w-3xl items-start gap-2 text-[13px] text-muted-foreground">
      <ShieldCheck className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-primary" aria-hidden />
      <span>
        {t('platformAdmin.scope')}{' '}
        <a
          href={docsUrl(i18n.language, 'self-hosting/platform-admin')}
          className="whitespace-nowrap text-foreground underline-offset-4 hover:underline"
        >
          {t('common.learnMore')}
        </a>
      </span>
    </p>
  );
}

/**
 * An email address in the panel. Cut with an ellipsis rather than broken mid-word — a table cell
 * ignores max-width, so the limit sits on this element — and the whole address is on hover. On a
 * phone, where rows are stacked cards, it takes the width the card has.
 */
export function EmailText({ email, className }: { email: string | null | undefined; className?: string }) {
  if (!email) return <span className="text-muted-foreground">—</span>;
  return (
    <span className={cn('block max-w-[13rem] truncate font-mono text-[13px] max-sm:max-w-full', className)} title={email}>
      {email}
    </span>
  );
}

/**
 * A link to one organization. One line with an ellipsis in a desktop table, the full name on
 * hover; on a phone the name wraps at word boundaries instead of being cut off.
 */
export function OrganizationLink({ id, name, className }: { id: string; name: string; className?: string }) {
  return (
    <Link
      to={`/admin/platform/organizations/${id}`}
      title={name}
      className={cn(
        'block max-w-[12rem] truncate underline-offset-4 hover:underline',
        'max-sm:max-w-full max-sm:whitespace-normal max-sm:break-words',
        className,
      )}
    >
      {name}
    </Link>
  );
}

/** Column titles stay on one line; a table wider than its card scrolls inside it instead. */
export const PLATFORM_TABLE_HEADER = '[&_th]:whitespace-nowrap';

/**
 * Slightly tighter cells than the dashboard default, so the widest panel table fits its card at a
 * 1440px screen. The phone layout sets its own cell padding and is not affected.
 */
export const PLATFORM_TABLE = '[&_td]:px-3 [&_th]:px-3';

/** An account the server counts as a platform admin, next to its row in the panel's lists. */
export function PlatformAdminBadge() {
  const { t } = useTranslation();
  return (
    <span className="inline-flex items-center gap-1 rounded border border-primary/30 bg-primary/10 px-1.5 py-0.5 text-[11px] font-medium text-primary">
      <ShieldCheck className="h-3 w-3" aria-hidden />
      {t('platformAdmin.adminBadge')}
    </span>
  );
}

export function OrganizationStatusBadge({ organization }: { organization: AdminOrganization }) {
  const { t } = useTranslation();
  if (organization.suspendedAt) {
    return <StatusBadge kind="halt" label={t('platformAdmin.status.suspended')} />;
  }
  if (organization.billingStatus === 'PAST_DUE') {
    return <StatusBadge kind="retry" label={t('platformAdmin.status.pastDue')} />;
  }
  return <StatusBadge kind="ok" label={t('platformAdmin.status.active')} />;
}

export function UserStatusBadge({ status }: { status: AdminUserStatus | null }) {
  const { t } = useTranslation();
  if (status === 'DISABLED') return <StatusBadge kind="halt" label={t('platformAdmin.userStatus.disabled')} />;
  if (status === 'PENDING_VERIFICATION') {
    return <StatusBadge kind="idle" label={t('platformAdmin.userStatus.pending')} />;
  }
  return <StatusBadge kind="ok" label={t('platformAdmin.userStatus.active')} />;
}

export function VerifiedBadge({ verified }: { verified: boolean }) {
  const { t } = useTranslation();
  return verified
    ? <StatusBadge kind="ok" label={t('platformAdmin.verified')} />
    : <StatusBadge kind="idle" label={t('platformAdmin.unverified')} />;
}

export function SignInMethods({ methods }: { methods: SignInMethod[] }) {
  const { t } = useTranslation();
  if (methods.length === 0) return <span className="text-muted-foreground">—</span>;
  const label = (method: string) => {
    if (method === 'PASSWORD') return t('platformAdmin.signIn.password');
    if (method === 'GOOGLE') return t('platformAdmin.signIn.google');
    return method;
  };
  return (
    <span className="inline-flex flex-wrap gap-1">
      {methods.map((method) => (
        <span key={method} className="rounded border border-rail px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground">
          {label(method)}
        </span>
      ))}
    </span>
  );
}

/**
 * Events this month against the plan's limit: the number, and a bar that warns from 80%. A plan
 * without a limit — every organization on a deployment with billing off — says so, rather than
 * showing a fraction of infinity and an empty bar.
 */
export function EventsAgainstLimit({ current, limit }: { current: number; limit: number }) {
  const { t } = useTranslation();
  if (limit <= 0) {
    return (
      <div className="min-w-[7rem] font-mono text-[13px]">
        {formatNumber(current)}
        <span className="ml-1.5 font-sans text-xs text-muted-foreground">{t('platformAdmin.unlimited')}</span>
      </div>
    );
  }
  const percent = Math.min(100, Math.round((current / limit) * 100));
  return (
    <div className="min-w-[7rem]">
      <div className="font-mono text-[13px]">
        {formatNumber(current)}
        <span className="text-muted-foreground"> / {formatNumber(limit)}</span>
      </div>
      <div className="mt-1 h-1 w-full rounded-full bg-secondary" aria-hidden>
        <div
          className={cn('h-1 rounded-full', percent >= 100 ? 'bg-halt' : percent >= 80 ? 'bg-retry' : 'bg-primary')}
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  );
}

export function PanelTitle({ title, meta }: { title: string; meta?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-rail px-4 py-3">
      <h2 className="text-[15px] font-medium">{title}</h2>
      {meta !== undefined && <span className="mono-label">{meta}</span>}
    </div>
  );
}

/**
 * Suspending or reinstating an organization: type its name back, and say why.
 *
 * The typed name is the same ritual every irreversible action in the product uses; the reason is
 * what the tenant is shown when suspended, and what the audit log keeps when reinstated. Neither
 * button unlocks until both are there.
 */
export function SuspensionDialog({
  organization, mode, open, onOpenChange,
}: {
  organization: AdminOrganization;
  mode: 'suspend' | 'reinstate';
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation();
  const nameId = useId();
  const reasonId = useId();
  const [typed, setTyped] = useState('');
  const [reason, setReason] = useState('');
  const suspend = useSuspendOrganization();
  const reinstate = useReinstateOrganization();
  const mutation = mode === 'suspend' ? suspend : reinstate;

  useEffect(() => {
    if (!open) {
      setTyped('');
      setReason('');
    }
  }, [open]);

  const ready = typed === organization.name && reason.trim().length > 0;
  const suspending = mode === 'suspend';

  const submit = async () => {
    if (!ready) return;
    try {
      await mutation.mutateAsync({ id: organization.id, reason: reason.trim() });
      showSuccess(t(suspending ? 'platformAdmin.suspendDialog.suspended' : 'platformAdmin.suspendDialog.reinstated',
        { name: organization.name }));
      onOpenChange(false);
    } catch (err) {
      showApiError(err, 'platformAdmin.suspendDialog.failed');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className={cn(suspending && 'text-halt')}>
            {t(suspending ? 'platformAdmin.suspendDialog.suspendTitle' : 'platformAdmin.suspendDialog.reinstateTitle',
              { name: organization.name })}
          </DialogTitle>
          <DialogDescription>
            {t(suspending
              ? 'platformAdmin.suspendDialog.suspendDescription'
              : 'platformAdmin.suspendDialog.reinstateDescription')}
          </DialogDescription>
        </DialogHeader>
        <form
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <div className="space-y-1.5">
            <Label htmlFor={nameId}>{t('platformAdmin.suspendDialog.typeName', { name: organization.name })}</Label>
            <Input id={nameId} value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor={reasonId}>{t('platformAdmin.suspendDialog.reason')}</Label>
            <Textarea
              id={reasonId}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={REASON_MAX}
              rows={3}
            />
            <p className="text-xs text-muted-foreground">
              {t(suspending ? 'platformAdmin.suspendDialog.suspendReasonHint' : 'platformAdmin.suspendDialog.reinstateReasonHint')}
            </p>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant={suspending ? 'destructive' : 'default'} disabled={!ready || mutation.isPending}>
              {mutation.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
              {t(suspending ? 'platformAdmin.suspendDialog.confirmSuspend' : 'platformAdmin.suspendDialog.confirmReinstate')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
