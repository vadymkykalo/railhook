import { useEffect, useRef, useState } from 'react';

import { captchaScriptUrl, captchaSiteKey } from '../lib/runtimeConfig';

/**
 * The CAPTCHA challenge, when the deployment has one.
 *
 * <p>Renders nothing at all unless the container sets RAILHOOK_CAPTCHA_SITE_KEY, which is the
 * shipped default: a self-hosted registration page has nobody to challenge, and loading a
 * third-party script on every visit to prove otherwise would be a worse default than not. The
 * server side mirrors this exactly — an unconfigured deployment accepts a registration with no
 * token.
 *
 * <p>Turnstile and hCaptcha expose the same `render(container, {sitekey, callback})` shape, so
 * RAILHOOK_CAPTCHA_SCRIPT_URL is what picks between them rather than a second component.
 */
interface Props {
  onToken: (token: string) => void;
}

declare global {
  interface Window {
    turnstile?: { render: (el: HTMLElement, opts: Record<string, unknown>) => void };
    hcaptcha?: { render: (el: HTMLElement, opts: Record<string, unknown>) => void };
  }
}

export function isCaptchaConfigured(): boolean {
  return Boolean(captchaSiteKey());
}

export default function CaptchaWidget({ onToken }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);
  const siteKey = captchaSiteKey();
  const scriptUrl = captchaScriptUrl();

  useEffect(() => {
    if (!siteKey || !container.current) return;

    const render = () => {
      const api = window.turnstile ?? window.hcaptcha;
      if (!api || !container.current) {
        setFailed(true);
        return;
      }
      api.render(container.current, { sitekey: siteKey, callback: onToken });
    };

    const existing = document.querySelector<HTMLScriptElement>(`script[src="${scriptUrl}"]`);
    if (existing) {
      render();
      return;
    }

    const script = document.createElement('script');
    script.src = scriptUrl;
    script.async = true;
    script.defer = true;
    script.onload = render;
    // A challenge that cannot load is worth saying out loud: the server refuses a registration
    // with no token, so silently rendering nothing would look like a broken submit button.
    script.onerror = () => setFailed(true);
    document.head.appendChild(script);
  }, [onToken, siteKey, scriptUrl]);

  if (!siteKey) return null;

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
