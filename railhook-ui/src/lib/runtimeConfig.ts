/**
 * Settings that belong to the running container rather than to the image.
 *
 * The published image is compiled once for every deployment of it — the hosted cloud and each
 * self-hosted install alike. What has to differ between those deployments is written by the UI
 * container when it starts, into a script the page loads before the app: `window.__RAILHOOK__`.
 *
 * Read on every call rather than captured at import, so nothing freezes the value the module
 * happened to see first.
 */
export interface RuntimeConfig {
  /** Domain behind the sales@ / support@ addresses. Empty on a self-hosted install. */
  contactDomain?: string;
  /** Public origin, e.g. https://railhook.io. Empty when the deployment has not declared one. */
  siteUrl?: string;
  /** Registration challenge site key. Empty means no challenge. */
  captchaSiteKey?: string;
  /** The challenge provider's script. Only set alongside a site key. */
  captchaScriptUrl?: string;
  /** Cloudflare Web Analytics token. Empty means no analytics, which is the default. */
  webAnalyticsToken?: string;
  /** Whether the public webhook tester (/tester) is on. Off unless the deployment says so. */
  publicTester?: boolean;
}

declare global {
  interface Window {
    __RAILHOOK__?: RuntimeConfig;
  }
}

function read(key: Exclude<keyof RuntimeConfig, 'publicTester'>): string | undefined {
  const value = typeof window !== 'undefined' ? window.__RAILHOOK__?.[key] : undefined;
  return (typeof value === 'string' && value.trim()) || undefined;
}

/** The configured contact domain, trimmed; undefined when none is configured. */
export function contactDomain(): string | undefined {
  return read('contactDomain');
}

/** The configured public origin, trimmed; undefined when none is configured. */
export function configuredSiteUrl(): string | undefined {
  return read('siteUrl');
}

/** The registration challenge's site key; undefined when the challenge is off. */
export function captchaSiteKey(): string | undefined {
  return read('captchaSiteKey');
}

/** The challenge script; Turnstile's when a site key is set without one. */
export function captchaScriptUrl(): string {
  return read('captchaScriptUrl') ?? 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
}

/** The Cloudflare Web Analytics token; undefined when analytics is off. */
export function webAnalyticsToken(): string | undefined {
  return read('webAnalyticsToken');
}

/** Whether the public webhook tester is on for this deployment. */
export function publicTesterEnabled(): boolean {
  return typeof window !== 'undefined' && window.__RAILHOOK__?.publicTester === true;
}
