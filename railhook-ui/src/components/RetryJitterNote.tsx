import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';

/**
 * The ladder a person types is the middle of each wait, not the wait. The worker jitters every step
 * between half and one and a half of it, so a thousand failed deliveries do not all come back in
 * the same second — and so a retry at 32s on a 60s step is the design, not a bug.
 */
export default function RetryJitterNote({ className }: { className?: string }) {
  const { t } = useTranslation();
  return <p className={cn('text-[11px] text-muted-foreground', className)}>{t('common.retryJitterNote')}</p>;
}
