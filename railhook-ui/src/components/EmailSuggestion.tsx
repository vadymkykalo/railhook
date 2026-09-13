import { Trans, useTranslation } from 'react-i18next';
import { hasImpossibleTld, suggestEmail } from '../lib/emailTypos';

/**
 * "Did you mean …?" under an address field, with the likely address as the one-click fix.
 *
 * Two voices, matching the two strengths in `emailTypos`: an ending that can never receive mail
 * is an alert, and the form it sits in refuses to submit it; a near miss of a popular domain is
 * a quiet hint, because it might be real.
 */
export default function EmailSuggestion({
  email, onAccept, id,
}: {
  email: string;
  onAccept: (suggestion: string) => void;
  id?: string;
}) {
  const { t } = useTranslation();
  const suggestion = suggestEmail(email);
  const impossible = hasImpossibleTld(email);
  if (!suggestion && !impossible) return null;

  const offer = suggestion && (
    <Trans
      i18nKey="common.emailSuggestion.didYouMean"
      values={{ email: suggestion }}
      components={{
        suggestion: (
          <button
            type="button"
            onClick={() => onAccept(suggestion)}
            className="font-mono font-medium text-foreground underline underline-offset-2 hover:text-primary"
          />
        ),
      }}
    />
  );

  if (impossible) {
    return (
      <p id={id} role="alert" className="text-xs text-halt">
        {t('common.emailSuggestion.impossible')} {offer}
      </p>
    );
  }
  return (
    <p id={id} role="status" className="text-xs text-muted-foreground">
      {offer}
    </p>
  );
}
