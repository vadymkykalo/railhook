import { useId } from 'react';
import { Check, Copy, Link2, Mail, MailX } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { useCopyToClipboard } from '../hooks/useCopyToClipboard';
import { Button } from './ui/button';
import { Input } from './ui/input';

interface InviteLinkProps {
  /** Who the invite is for — named so the owner knows who to send it to. */
  email: string;
  /** The accept-invite address, as the server built it. */
  url: string;
  /** Whether this deployment could actually mail it. */
  emailDelivered: boolean;
}

/**
 * The invite link, shown to the owner who just issued it.
 *
 * It is here because `EMAIL_ENABLED=false` is the shipped default and the README
 * sells "no SMTP needed": with mail off the invite token reached the API
 * container's log and nowhere else, while the browser was told the invitation had
 * been sent. So the link is put in front of the person who can pass it on, and the
 * copy says plainly whether anything was sent.
 *
 * The temporary password minted for a brand-new invitee is deliberately not here
 * and not anywhere — it is non-expiring account access. The invitee accepts with
 * this link and sets a password through forgot-password.
 */
export default function InviteLink({ email, url, emailDelivered }: InviteLinkProps) {
  const { t } = useTranslation();
  const { copied, copy } = useCopyToClipboard();
  const fieldId = useId();

  const handleCopy = async () => {
    if (await copy(url)) {
      showSuccess(t('members.toast.linkCopied'));
    } else {
      showApiError(new Error('clipboard'), 'members.toast.copyFailed');
    }
  };

  const Icon = emailDelivered ? Mail : MailX;

  return (
    <div className="space-y-2.5 rounded-md border border-rail bg-secondary/50 p-3">
      <p className="flex items-start gap-2 text-[13px] text-muted-foreground">
        <Icon className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" aria-hidden />
        <span>
          {emailDelivered
            ? t('members.invite.emailed', { email })
            : t('members.invite.emailDisabled', { email })}
        </span>
      </p>

      <div className="space-y-1.5">
        <label className="mono-label flex items-center gap-1.5" htmlFor={fieldId}>
          <Link2 className="h-3 w-3" aria-hidden />
          {t('members.addModal.inviteLink')}
        </label>
        <div className="flex gap-2">
          <Input
            id={fieldId}
            readOnly
            value={url}
            onFocus={(e) => e.currentTarget.select()}
            className="font-mono text-[12px]"
          />
          <Button type="button" variant="outline" onClick={handleCopy} className="flex-shrink-0">
            {copied ? <Check className="h-4 w-4" aria-hidden /> : <Copy className="h-4 w-4" aria-hidden />}
            {t('members.addModal.copyLink')}
          </Button>
        </div>
        <p className="text-xs text-muted-foreground">{t('members.addModal.inviteLinkHint')}</p>
      </div>
    </div>
  );
}
