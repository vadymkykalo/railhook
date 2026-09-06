import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, X } from 'lucide-react';
import { cn } from '../lib/utils';

interface PasswordStrengthIndicatorProps {
  password: string;
  className?: string;
}

interface Rule {
  key: string;
  test: (p: string) => boolean;
}

const RULES: Rule[] = [
  { key: 'minLength', test: (p) => p.length >= 8 },
  { key: 'uppercase', test: (p) => /[A-Z]/.test(p) },
  { key: 'lowercase', test: (p) => /[a-z]/.test(p) },
  { key: 'digit', test: (p) => /\d/.test(p) },
  { key: 'special', test: (p) => /[^A-Za-z0-9]/.test(p) },
];

/**
 * Whether every rule the checklist draws is satisfied.
 *
 * The form has to gate on the same list the user is looking at. Before this,
 * the submit button stayed enabled while the checklist showed a red cross, so
 * the only way to learn the password was rejected was to send it and read a
 * server error — which said "Invalid request parameters" and named no field.
 */
export function passwordMeetsPolicy(password: string): boolean {
  return RULES.every((r) => r.test(password));
}

/**
 * The meter reads in three steps, not four: rejected, not yet accepted,
 * accepted. "Fair" and "Good" are both "not yet strong", so they share a hue
 * and are told apart by the filled segment count and the label — which is what
 * a person actually reads. The bar never borrows the brand accent.
 */
const LEVEL_STYLE = {
  weak: { bar: 'bg-halt', text: 'text-halt' },
  fair: { bar: 'bg-retry', text: 'text-retry' },
  good: { bar: 'bg-retry', text: 'text-retry' },
  strong: { bar: 'bg-ok', text: 'text-ok' },
} as const;

export default function PasswordStrengthIndicator({ password, className }: PasswordStrengthIndicatorProps) {
  const { t } = useTranslation();

  const results = useMemo(
    () => RULES.map((r) => ({ ...r, passed: r.test(password) })),
    [password],
  );

  const strength = results.filter((r) => r.passed).length;
  const level = strength <= 1 ? 'weak' : strength <= 3 ? 'fair' : strength <= 4 ? 'good' : 'strong';
  const style = LEVEL_STYLE[level];

  if (!password) return null;

  return (
    <div className={cn('space-y-2 pt-1', className)}>
      <div className="flex items-center gap-2">
        <div className="flex flex-1 gap-1">
          {Array.from({ length: RULES.length }).map((_, i) => (
            <div
              key={i}
              className={cn(
                'h-1 flex-1 rounded-full transition-colors duration-200',
                i < strength ? style.bar : 'bg-rail',
              )}
            />
          ))}
        </div>
        <span className={cn('font-mono text-[11px] font-medium uppercase tracking-[0.08em]', style.text)}>
          {t(`passwordStrength.${level}`)}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-x-4 gap-y-0.5">
        {results.map((r) => (
          <div key={r.key} className="flex items-center gap-1.5 text-[11px]">
            {r.passed ? (
              <Check className="h-3 w-3 flex-shrink-0 text-ok" aria-hidden />
            ) : (
              <X className="h-3 w-3 flex-shrink-0 text-muted-foreground/50" aria-hidden />
            )}
            <span className={r.passed ? 'text-foreground' : 'text-muted-foreground'}>
              {t(`passwordStrength.rules.${r.key}`)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
