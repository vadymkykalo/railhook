import { RailhookIcon } from './icons/RailhookIcon';

/** No words: i18n is still loading here, so any text would be hardcoded English. */
export default function BootSplash() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <RailhookIcon
        className="h-10 w-10 animate-pulse text-primary"
        role="img"
        aria-label="Railhook"
      />
    </div>
  );
}
