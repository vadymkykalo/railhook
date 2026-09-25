import { useEffect, useRef, useState } from 'react';

import { captchaScriptUrl, captchaSiteKey } from '../lib/runtimeConfig';

/** Renders nothing unless RAILHOOK_CAPTCHA_SITE_KEY is set; the script URL picks Turnstile or hCaptcha. */
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
    // The server refuses a tokenless registration, so a silent failure would look like a broken submit.
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
