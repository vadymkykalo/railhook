import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';

/** The worker jitters each step 0.5x-1.5x, so a 32s retry on a 60s step is by design. */
export default function RetryJitterNote({ className }: { className?: string }) {
  const { t } = useTranslation();
  return <p className={cn('text-[11px] text-muted-foreground', className)}>{t('common.retryJitterNote')}</p>;
}
