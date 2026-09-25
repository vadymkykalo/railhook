import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import type { SignatureScheme } from '../types/api.types';
import { cn } from '../lib/utils';

/** BOTH leads: it is the column default, and receivers ignore headers they do not read. */
const OPTIONS: { scheme: SignatureScheme; headers: string }[] = [
  { scheme: 'BOTH', headers: 'X-Signature · webhook-id / webhook-timestamp / webhook-signature' },
  { scheme: 'STANDARD', headers: 'webhook-id / webhook-timestamp / webhook-signature' },
  { scheme: 'LEGACY', headers: 'X-Signature' },
];

function optionKey(scheme: SignatureScheme): string {
  return scheme.toLowerCase();
}

/** Undefined is the column default (BOTH), so it sends both header sets. */
export function sendsStandardHeaders(scheme: SignatureScheme | undefined): boolean {
  return (scheme ?? 'BOTH') !== 'LEGACY';
}

interface SignatureSchemePickerProps {
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
                'block w-full border p-3 text-left transition-colors',
                active
                  ? 'border-primary bg-secondary'
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
