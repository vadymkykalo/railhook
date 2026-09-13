/**
 * Settings that belong to the running container rather than to the image.
 *
 * `VITE_*` values are inlined when the bundle is compiled, and the published image is compiled
 * once for every deployment of it — the hosted cloud and each self-hosted install alike. What has
 * to differ between those deployments is written by the UI container when it starts, into a
 * script the page loads before the app: `window.__RAILHOOK__`.
 *
 * Read on every call rather than captured at import, so nothing freezes the value the module
 * happened to see first.
 */
export interface RuntimeConfig {
  /** Domain behind the sales@ / support@ addresses. Empty on a self-hosted install. */
  contactDomain?: string;
}

declare global {
  interface Window {
    __RAILHOOK__?: RuntimeConfig;
  }
}

/** The configured contact domain, trimmed; undefined when none is configured. */
export function contactDomain(): string | undefined {
  const value = window.__RAILHOOK__?.contactDomain;
  return (typeof value === 'string' && value.trim()) || undefined;
}
