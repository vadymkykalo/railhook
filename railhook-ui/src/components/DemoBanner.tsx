import { useTranslation } from 'react-i18next';
import { Eye, LogOut } from 'lucide-react';
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
 *
 * <p>On a phone it is one line — two words, the one thing to do, and an icon to leave — because
 * the full sentence and two labelled buttons wrapped onto several lines above every page and pushed
 * the page's own heading below the fold.
 */
export default function DemoBanner({ onStartFree, onExit }: DemoBannerProps) {
  const { t } = useTranslation();

  return (
    <div role="region" aria-label={t('demo.banner')} className="border-b border-primary/20 bg-accent px-4 py-1.5 sm:py-2.5 lg:px-6">
      <div className="flex items-center justify-between gap-x-4 gap-y-2 sm:flex-wrap">
        <p className="flex min-w-0 items-center gap-2 text-sm text-foreground">
          <Eye className="h-4 w-4 flex-shrink-0 text-primary" aria-hidden="true" />
          <strong className="truncate font-semibold sm:hidden">{t('demo.bannerShort')}</strong>
          <span className="max-sm:hidden">
            <strong className="font-semibold">{t('demo.banner')}</strong> {t('demo.bannerBody')}
          </span>
        </p>
        <div className="flex flex-shrink-0 items-center gap-1 sm:gap-2">
          <Button size="sm" onClick={onStartFree}>
            {t('demo.startFree')}
          </Button>
          <Button size="sm" variant="ghost" onClick={onExit} aria-label={t('demo.exit')} className="max-sm:px-2">
            <LogOut className="h-4 w-4 sm:hidden" aria-hidden="true" />
            <span className="max-sm:hidden">{t('demo.exit')}</span>
          </Button>
        </div>
      </div>
    </div>
  );
}
