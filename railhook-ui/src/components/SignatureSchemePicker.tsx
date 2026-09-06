import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import type { SignatureScheme } from '../types/api.types';
import { cn } from '../lib/utils';

/**
 * Which signature headers an endpoint is sent.
 *
 * The three options are told apart by their words and by the header names they
 * list, never by colour — the status hues mean something else everywhere else in
 * the dashboard, and none of these is a failure.
 *
 * `BOTH` leads because it is the column default and the only choice that costs
 * nothing: a receiver ignores the headers it does not read, so an endpoint sent
 * both sets keeps verifying whichever one it already knows.
 */
const OPTIONS: { scheme: SignatureScheme; headers: string }[] = [
  { scheme: 'BOTH', headers: 'X-Signature · webhook-id / webhook-timestamp / webhook-signature' },
  { scheme: 'STANDARD', headers: 'webhook-id / webhook-timestamp / webhook-signature' },
  { scheme: 'LEGACY', headers: 'X-Signature' },
];

/** The translation key stem for one option — `BOTH` lives under `signatureScheme.both`. */
function optionKey(scheme: SignatureScheme): string {
  return scheme.toLowerCase();
}

/**
 * Whether this scheme puts the Standard Webhooks headers on a delivery, and so
 * whether the `whsec_…` secret is one the receiver has any use for.
 *
 * Undefined is the column default: an endpoint that predates
 * `V062__endpoint_signature_scheme.sql` is sent both header sets.
 */
export function sendsStandardHeaders(scheme: SignatureScheme | undefined): boolean {
  return (scheme ?? 'BOTH') !== 'LEGACY';
}

interface SignatureSchemePickerProps {
  /** Undefined reads as the column default, `BOTH`, rather than as no choice. */
  value: SignatureScheme | undefined;
  onChange: (scheme: SignatureScheme) => void;
  disabled?: boolean;
}

export default function SignatureSchemePicker({ value, onChange, disabled }: SignatureSchemePickerProps) {
  const { t } = useTranslation();
  const id = useId();
  const selected = value ?? 'BOTH';

  return (
    <div className="space-y-2.5">
      <div>
        <div className="mono-label" id={`${id}-label`}>{t('signatureScheme.label')}</div>
        <p className="mt-1.5 text-xs leading-relaxed text-muted-foreground">{t('signatureScheme.desc')}</p>
      </div>
      <div className="space-y-2" role="radiogroup" aria-labelledby={`${id}-label`}>
        {OPTIONS.map(({ scheme, headers }) => {
          const key = optionKey(scheme);
          const active = selected === scheme;
          return (
            <button
              key={scheme}
              type="button"
              role="radio"
              aria-checked={active}
              aria-labelledby={`${id}-${key}-title`}
              aria-describedby={`${id}-${key}-desc`}
              disabled={disabled}
              onClick={() => onChange(scheme)}
              className={cn(
                'block w-full rounded-lg border p-3 text-left transition-colors',
                active
                  ? 'border-primary bg-accent'
                  : 'border-rail bg-card hover:border-primary/40 hover:bg-secondary/50',
                disabled && 'cursor-not-allowed opacity-60 hover:border-rail hover:bg-card'
              )}
            >
              <span className="block text-sm font-medium" id={`${id}-${key}-title`}>
                {t(`signatureScheme.${key}.title`)}
              </span>
              <span className="mt-0.5 block text-xs leading-relaxed text-muted-foreground" id={`${id}-${key}-desc`}>
                {t(`signatureScheme.${key}.desc`)}
              </span>
              <code className="mt-1.5 block break-all font-mono text-[11px] text-muted-foreground">
                {headers}
              </code>
            </button>
          );
        })}
      </div>
    </div>
  );
}
