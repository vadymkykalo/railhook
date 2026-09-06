/**
 * Injects Content-Security-Policy meta tag dynamically based on environment variables.
 *
 * Environment variables (set via VITE_ prefix):
 *   VITE_API_URL          — API origin (e.g. https://api.example.com). Empty = same origin.
 *   VITE_CSP_EXTRA_CONNECT — additional connect-src origins, space-separated.
 *   VITE_CAPTCHA_SITE_KEY  — presence of this turns the registration challenge on, which is
 *                            what widens script-src and frame-src below.
 *
 * In development (localhost), connect-src automatically includes http://localhost:* and ws://localhost:*.
 * In production, only 'self' + VITE_API_URL origin are allowed.
 */
export function initCSP() {
  const apiUrl = import.meta.env.VITE_API_URL || '';
  const extraConnect = import.meta.env.VITE_CSP_EXTRA_CONNECT || '';
  const isDev = import.meta.env.DEV;

  /*
   * The CAPTCHA is a third-party script and an iframe, so a deployment that configures one has
   * to allow the origin it comes from — otherwise the widget is blocked and the registration
   * page has a submit button that can never be enabled. Derived from the script URL rather
   * than a separate variable, so the two cannot disagree; widened only when a site key is set,
   * so a self-hosted deployment keeps the tighter policy it has today.
   */
  const captchaEnabled = Boolean(import.meta.env.VITE_CAPTCHA_SITE_KEY);
  const captchaScriptUrl = (import.meta.env.VITE_CAPTCHA_SCRIPT_URL as string | undefined)
    ?? 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
  let captchaOrigin = '';
  if (captchaEnabled) {
    try {
      captchaOrigin = new URL(captchaScriptUrl).origin;
    } catch {
      captchaOrigin = '';
    }
  }

  // Build connect-src
  const connectSources = new Set<string>(["'self'"]);

  if (apiUrl) {
    try {
      const origin = new URL(apiUrl).origin;
      connectSources.add(origin);
    } catch {
      connectSources.add(apiUrl);
    }
  }

  if (isDev) {
    connectSources.add('http://localhost:*');
    connectSources.add('https://localhost:*');
    connectSources.add('ws://localhost:*');
    connectSources.add('wss://localhost:*');
  }

  if (extraConnect) {
    extraConnect.split(/\s+/).forEach((src: string) => {
      if (src) connectSources.add(src);
    });
  }

  if (captchaOrigin) {
    connectSources.add(captchaOrigin);
  }

  const scriptSources = ["'self'"];
  const frameSources: string[] = [];
  if (captchaOrigin) {
    scriptSources.push(captchaOrigin);
    frameSources.push(captchaOrigin);
  }

  const policy = [
    "default-src 'self'",
    `script-src ${scriptSources.join(' ')}`,
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data: blob:",
    `connect-src ${[...connectSources].join(' ')}`,
    frameSources.length ? `frame-src ${frameSources.join(' ')}` : "frame-src 'none'",
    "object-src 'none'",
    "base-uri 'self'",
  ].join('; ');

  const meta = document.createElement('meta');
  meta.httpEquiv = 'Content-Security-Policy';
  meta.content = policy;
  document.head.prepend(meta);
}
