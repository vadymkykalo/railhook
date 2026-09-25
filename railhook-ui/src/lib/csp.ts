import { captchaScriptUrl, captchaSiteKey, webAnalyticsToken } from './runtimeConfig';

export function initCSP() {
  const apiUrl = import.meta.env.VITE_API_URL || '';
  const extraConnect = import.meta.env.VITE_CSP_EXTRA_CONNECT || '';
  const isDev = import.meta.env.DEV;

  /* Widened only when a captcha key is set; origin derived from the script URL so the two cannot disagree. */
  let captchaOrigin = '';
  if (captchaSiteKey()) {
    try {
      captchaOrigin = new URL(captchaScriptUrl()).origin;
    } catch {
      captchaOrigin = '';
    }
  }

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

  if (webAnalyticsToken()) {
    scriptSources.push('https://static.cloudflareinsights.com');
    connectSources.add('https://cloudflareinsights.com');
  }

  const policy = [
    "default-src 'self'",
    `script-src ${scriptSources.join(' ')}`,
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    // Only the portal shows a foreign (https) logo, so only it widens img-src.
    window.location.pathname === '/portal' ? "img-src 'self' data: blob: https:" : "img-src 'self' data: blob:",
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
