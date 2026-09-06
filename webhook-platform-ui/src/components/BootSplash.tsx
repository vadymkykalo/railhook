import { RailhookIcon } from './icons/RailhookIcon';

/**
 * The first thing every visitor sees, and the only screen that renders before
 * anything else is ready.
 *
 * <p>It carries no words on purpose. This is the one moment the app cannot
 * translate itself — i18n is still loading, which is half of why it is on
 * screen — and the "Loading..." it used to print was therefore hardcoded
 * English for every Ukrainian visitor. A mark says the same thing in both
 * languages.
 *
 * <p>It is also the mark rather than a spinner ring, because a bare ring is the
 * one piece of chrome in this product that would look identical in any other.
 * Under `prefers-reduced-motion` the global rule in index.css stops the pulse
 * and leaves the mark sitting still, which is a perfectly good boot screen.
 */
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
