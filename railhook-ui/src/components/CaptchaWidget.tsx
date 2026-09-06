import { useEffect, useRef, useState } from 'react';

/**
 * The CAPTCHA challenge, when the deployment has one.
 *
 * <p>Renders nothing at all unless `VITE_CAPTCHA_SITE_KEY` is set, which is the shipped
 * default: a self-hosted registration page has nobody to challenge, and loading a third-party
 * script on every visit to prove otherwise would be a worse default than not. The server side
 * mirrors this exactly — an unconfigured deployment accepts a registration with no token.
 *
 * <p>Turnstile and hCaptcha expose the same `render(container, {sitekey, callback})` shape, so
 * `VITE_CAPTCHA_SCRIPT_URL` is what picks between them rather than a second component.
 */
interface Props {
  onToken: (token: string) => void;
}

const SITE_KEY = import.meta.env.VITE_CAPTCHA_SITE_KEY as string | undefined;
const SCRIPT_URL = (import.meta.env.VITE_CAPTCHA_SCRIPT_URL as string | undefined)
  ?? 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

declare global {
  interface Window {
    turnstile?: { render: (el: HTMLElement, opts: Record<string, unknown>) => void };
    hcaptcha?: { render: (el: HTMLElement, opts: Record<string, unknown>) => void };
  }
}

export function isCaptchaConfigured(): boolean {
  return Boolean(SITE_KEY);
}

export default function CaptchaWidget({ onToken }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!SITE_KEY || !container.current) return;

    const render = () => {
      const api = window.turnstile ?? window.hcaptcha;
      if (!api || !container.current) {
        setFailed(true);
        return;
      }
      api.render(container.current, { sitekey: SITE_KEY, callback: onToken });
    };

    const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_URL}"]`);
    if (existing) {
      render();
      return;
    }

    const script = document.createElement('script');
    script.src = SCRIPT_URL;
    script.async = true;
    script.defer = true;
    script.onload = render;
    // A challenge that cannot load is worth saying out loud: the server refuses a registration
    // with no token, so silently rendering nothing would look like a broken submit button.
    script.onerror = () => setFailed(true);
    document.head.appendChild(script);
  }, [onToken]);

  if (!SITE_KEY) return null;

  return (
    <div>
      <div ref={container} />
      {failed && (
        <p className="text-sm text-halt" role="alert">
          The verification challenge could not be loaded. Check your connection and reload.
        </p>
      )}
    </div>
  );
}
