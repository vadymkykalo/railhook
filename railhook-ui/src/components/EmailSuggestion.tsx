import { Trans, useTranslation } from 'react-i18next';
import { hasImpossibleTld, suggestEmail } from '../lib/emailTypos';

/** An impossible ending is an alert that blocks submit; a near miss is a hint, since it might be real. */
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
            className="font-mono font-medium text-foreground underline underline-offset-2 hover:text-foreground"
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
