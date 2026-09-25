/** Read on every call, not at import, so nothing freezes the first value seen. */
export interface RuntimeConfig {
  contactDomain?: string;
  siteUrl?: string;
  captchaSiteKey?: string;
  captchaScriptUrl?: string;
  webAnalyticsToken?: string;
  statusPageUrl?: string;
  publicTester?: boolean;
  publicDemo?: boolean;
  publicBlog?: boolean;
}

declare global {
  interface Window {
    __RAILHOOK__?: RuntimeConfig;
  }
}

function read(key: Exclude<keyof RuntimeConfig, 'publicTester' | 'publicDemo' | 'publicBlog'>): string | undefined {
  const value = typeof window !== 'undefined' ? window.__RAILHOOK__?.[key] : undefined;
  return (typeof value === 'string' && value.trim()) || undefined;
}

export function contactDomain(): string | undefined {
  return read('contactDomain');
}

export function configuredSiteUrl(): string | undefined {
  return read('siteUrl');
}

export function captchaSiteKey(): string | undefined {
  return read('captchaSiteKey');
}

export function captchaScriptUrl(): string {
  return read('captchaScriptUrl') ?? 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
}

export function webAnalyticsToken(): string | undefined {
  return read('webAnalyticsToken');
}

export function statusPageUrl(): string | undefined {
  return read('statusPageUrl');
}

export function publicTesterEnabled(): boolean {
  return typeof window !== 'undefined' && window.__RAILHOOK__?.publicTester === true;
}

export function publicDemoEnabled(): boolean {
  return typeof window !== 'undefined' && window.__RAILHOOK__?.publicDemo === true;
}

export function publicBlogEnabled(): boolean {
  return typeof window !== 'undefined' && window.__RAILHOOK__?.publicBlog === true;
}
