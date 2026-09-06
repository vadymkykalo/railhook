import { useState } from 'react';
import { Send, Radio, ArrowLeftRight, ArrowRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from './ui/button';
import { cn } from '../lib/utils';
import type { Track } from '../lib/onboarding';

export type WebhookIntent = Track;

interface IntentPickerProps {
  onSelect: (intent: WebhookIntent) => void;
  /** The answer already on record, when the picker is re-asking. */
  value?: WebhookIntent | null;
}

/**
 * The three choices are the two directions and both, so they are told apart by
 * their icons and their words — never by colour. The status hues are reserved,
 * and the only accent here is the brand mark on the selected card.
 */
const INTENTS: { key: WebhookIntent; icon: React.ElementType }[] = [
  { key: 'send', icon: Send },
  { key: 'receive', icon: Radio },
  { key: 'both', icon: ArrowLeftRight },
];

export default function IntentPicker({ onSelect, value = null }: IntentPickerProps) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<WebhookIntent | null>(value);

  /**
   * The picker asks; it does not remember. It used to write two localStorage
   * keys of its own, and both callers then ignored the answer — which is how
   * the question came to be asked and thrown away. Storage lives in
   * `src/lib/onboarding.ts` now, and the caller decides what the answer means.
   */
  const handleContinue = () => {
    if (selected) onSelect(selected);
  };

  return (
    <div className="space-y-5">
      <div className="space-y-2.5" role="radiogroup" aria-label={t('auth.intent.title')}>
        {INTENTS.map(({ key, icon: Icon }) => {
          const active = selected === key;
          return (
            <button
              key={key}
              type="button"
              role="radio"
              aria-checked={active}
              onClick={() => setSelected(key)}
              className={cn(
                'flex w-full items-start gap-3.5 rounded-lg border p-4 text-left transition-colors',
                active
                  ? 'border-primary bg-accent'
                  : 'border-rail bg-card hover:border-primary/40 hover:bg-secondary/50',
              )}
            >
              <div
                className={cn(
                  'flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-md border',
                  active ? 'border-primary/30 bg-primary text-primary-foreground' : 'border-rail bg-secondary text-muted-foreground',
                )}
              >
                <Icon className="h-4 w-4" aria-hidden />
              </div>
              <div className="min-w-0">
                <span className="block text-sm font-medium">{t(`auth.intent.${key}`)}</span>
                <span className="mt-0.5 block text-xs leading-relaxed text-muted-foreground">
                  {t(`auth.intent.${key}Desc`)}
                </span>
              </div>
            </button>
          );
        })}
      </div>

      <Button onClick={handleContinue} disabled={!selected} className="h-10 w-full">
        {t('auth.intent.continue')}
        <ArrowRight className="h-4 w-4" aria-hidden />
      </Button>
    </div>
  );
}
