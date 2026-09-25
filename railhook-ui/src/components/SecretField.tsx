import { useState } from 'react';
import { Check, Copy, Eye, EyeOff } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { useCopyToClipboard } from '../hooks/useCopyToClipboard';
import { Button } from './ui/button';

/**
 * A secret, never legible until asked for.
 *
 * A signing secret is the one field on this screen that must not survive a
 * screenshot, a shared screen or a scrolled-past terminal, so it renders as
 * dots until the reader asks for it and goes back to dots when the dialog
 * closes. Copy does not require revealing.
 */
export default function SecretField({ secret, label }: { secret: string; label?: string }) {
  const { t } = useTranslation();
  const [revealed, setRevealed] = useState(false);
  const { copied, copy: copyToClipboard } = useCopyToClipboard();

  const copy = async () => {
    if (await copyToClipboard(secret)) {
      showSuccess(t('endpoints.toast.secretCopied'));
    } else {
      showApiError(new Error('clipboard'), 'connectionSetup.secret.copyFailed');
    }
  };

  return (
    <div className="space-y-1.5">
      {label && <div className="mono-label">{label}</div>}
      <div className="flex w-full items-start gap-2">
        {/* w-0 + break-all: a 64-character secret must wrap inside the row,
            never widen the dialog it sits in. */}
        <code
          className="w-0 min-w-0 flex-1 break-all border border-rail bg-secondary/50 px-3 py-2 font-mono text-xs leading-6"
          data-testid="signing-secret"
        >
          {revealed ? secret : '•'.repeat(Math.min(secret.length, 48))}
        </code>
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="flex-shrink-0"
          onClick={() => setRevealed((v) => !v)}
          aria-label={revealed ? t('connectionSetup.secret.hide', 'Hide secret') : t('connectionSetup.secret.reveal', 'Reveal secret')}
          title={revealed ? t('connectionSetup.secret.hide', 'Hide secret') : t('connectionSetup.secret.reveal', 'Reveal secret')}
        >
          {revealed ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="flex-shrink-0"
          onClick={copy}
          aria-label={t('endpoints.secretDialog.copy')}
          title={t('endpoints.secretDialog.copy')}
        >
          {copied ? <Check className="h-4 w-4 text-ok" /> : <Copy className="h-4 w-4" />}
        </Button>
      </div>
    </div>
  );
}
