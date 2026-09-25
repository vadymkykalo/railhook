import { useTranslation } from 'react-i18next';
import { Eye } from 'lucide-react';
import { Button } from './ui/button';

interface DemoBannerProps {
  /** Ends the demo and opens registration. */
  onStartFree: () => void;
  /** Ends the demo and returns to the public site. */
  onExit: () => void;
}

/**
 * Said on every page of the live demo, above the content: this is somebody else's sample shop,
 * nothing here can be changed, and here is how to get one of your own.
 */
export default function DemoBanner({ onStartFree, onExit }: DemoBannerProps) {
  const { t } = useTranslation();

  return (
    <div role="region" aria-label={t('demo.banner')} className="border-b border-rail bg-accent px-4 py-2.5 text-accent-foreground lg:px-6">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <p className="flex items-center gap-2 text-sm">
          <Eye className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
          <span>
            <strong className="font-medium">{t('demo.banner')}</strong> {t('demo.bannerBody')}
          </span>
        </p>
        <div className="flex flex-shrink-0 items-center gap-2">
          <Button size="sm" onClick={onStartFree}>
            {t('demo.startFree')}
          </Button>
          <Button size="sm" variant="ghost" onClick={onExit}>
            {t('demo.exit')}
          </Button>
        </div>
      </div>
    </div>
  );
}
