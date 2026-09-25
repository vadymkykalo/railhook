import { useState } from 'react';
import { Send, Radio, ArrowLeftRight, ArrowRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from './ui/button';
import { cn } from '../lib/utils';
import type { Track } from '../lib/onboarding';

export type WebhookIntent = Track;

interface IntentPickerProps {
  onSelect: (intent: WebhookIntent) => void;
  value?: WebhookIntent | null;
}

/** Told apart by icon and words, never colour: the status hues are reserved. */
const INTENTS: { key: WebhookIntent; icon: React.ElementType }[] = [
  { key: 'send', icon: Send },
  { key: 'receive', icon: Radio },
  { key: 'both', icon: ArrowLeftRight },
];

export default function IntentPicker({ onSelect, value = null }: IntentPickerProps) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<WebhookIntent | null>(value);

  /** The picker doesn't store the answer; lib/onboarding.ts does, and the caller decides. */
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
                'flex w-full items-start gap-3.5 border p-4 text-left transition-colors',
                active
                  ? 'border-primary bg-secondary'
                  : 'border-rail bg-card hover:border-primary/40 hover:bg-secondary/50',
              )}
            >
              <div
                className={cn(
                  'flex h-9 w-9 flex-shrink-0 items-center justify-center border',
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
