import { useState } from 'react';
import { Loader2, Mail, MailX } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { membersApi, MemberResponse, MembershipRole } from '../api/members.api';
import { useAuth } from '../auth/auth.store';
import InviteLink from './InviteLink';
import { GRANTABLE_ROLES, RoleCard } from './PermissionGate';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog';

interface AddMemberModalProps {
  orgId: string;
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * Inviting somebody is granting them a role, so the role is chosen from cards
 * that say what it lets them do rather than from a dropdown of three words.
 *
 * The dialog does not close on success. An invite to a brand-new person comes back
 * with the accept-invite link, and with `EMAIL_ENABLED=false` — the shipped default —
 * that link is the only copy anybody will ever see; closing on a green "Invited"
 * toast is what made invites undeliverable in a default install.
 */
export default function AddMemberModal({ orgId, open, onClose, onSuccess }: AddMemberModalProps) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<MembershipRole>('DEVELOPER');
  const [adding, setAdding] = useState(false);
  const [emailError, setEmailError] = useState('');
  const [issued, setIssued] = useState<MemberResponse | null>(null);

  const emailDelivered = user?.emailDeliveryEnabled ?? false;

  const handleClose = () => {
    setEmail('');
    setRole('DEVELOPER');
    setEmailError('');
    setIssued(null);
    onClose();
  };

  const validate = (): boolean => {
    if (!email.trim()) {
      setEmailError(t('members.addModal.emailRequired'));
      return false;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      setEmailError(t('members.addModal.emailInvalid'));
      return false;
    }
    setEmailError('');
    return true;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    setAdding(true);
    try {
      const response = await membersApi.add(orgId, { email: email.trim(), role });
      onSuccess();
      if (response.inviteUrl) {
        // A pending invite: stay open on the link rather than claim it was sent.
        setIssued(response);
        return;
      }
      // Somebody who already had a Hookflow account: they are a member as of now,
      // there is no invite to accept and nothing to hand over.
      showSuccess(t('members.toast.added'));
      handleClose();
    } catch (err: any) {
      showApiError(err, 'members.toast.addFailed');
    } finally {
      setAdding(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t(issued ? 'members.addModal.issuedTitle' : 'members.addModal.title')}</DialogTitle>
          <DialogDescription>
            {t(issued ? 'members.addModal.issuedDescription' : 'members.addModal.description')}
          </DialogDescription>
        </DialogHeader>

        {issued?.inviteUrl ? (
          <div className="space-y-4 py-4">
            <InviteLink email={issued.email} url={issued.inviteUrl} emailDelivered={emailDelivered} />
            <DialogFooter>
              <Button type="button" onClick={handleClose}>{t('common.close')}</Button>
            </DialogFooter>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="space-y-5 py-4">
              <div className="space-y-2">
                <Label htmlFor="member-email">{t('members.addModal.email')}</Label>
                <Input
                  id="member-email"
                  type="email"
                  placeholder="teammate@example.com"
                  value={email}
                  onChange={(e) => { setEmail(e.target.value); setEmailError(''); }}
                  disabled={adding}
                  aria-invalid={!!emailError}
                  aria-describedby={emailError ? 'member-email-error' : undefined}
                  required
                  autoFocus
                />
                {emailError && (
                  <p id="member-email-error" className="text-sm text-halt">{emailError}</p>
                )}
              </div>

              <div className="space-y-2">
                <span className="text-sm font-medium leading-none">{t('members.addModal.role')}</span>
                <p className="text-xs text-muted-foreground">{t('members.addModal.roleHint')}</p>
                <div role="radiogroup" aria-label={t('members.addModal.role')} className="grid gap-2.5 pt-1 sm:grid-cols-2">
                  {GRANTABLE_ROLES.map((r) => (
                    <RoleCard
                      key={r}
                      role={r}
                      selected={role === r}
                      onSelect={(next) => setRole(next as MembershipRole)}
                      disabled={adding}
                    />
                  ))}
                </div>
                <p className="pt-1 text-xs text-muted-foreground">{t('members.addModal.ownerNote')}</p>
              </div>

              <p className="flex items-start gap-2 rounded-md border border-rail bg-secondary/50 p-3 text-[13px] text-muted-foreground">
                {emailDelivered
                  ? <Mail className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" aria-hidden />
                  : <MailX className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" aria-hidden />}
                {t(emailDelivered ? 'members.addModal.emailNote' : 'members.addModal.emailDisabledNote')}
              </p>
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={handleClose} disabled={adding}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" disabled={adding}>
                {adding && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
                {adding ? t('members.addModal.adding') : t('members.addModal.submit', { role: t(`roles.${role}.name`) })}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
