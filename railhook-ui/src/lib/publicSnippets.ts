import type { ProviderType } from '../types/api.types';

/** The port is positional; `-p` is `--project`. */
export const CLI_LISTEN_EXAMPLE = 'railhook listen 3000';

/** Not the site URL (APP_BASE_URL): a stale one pointed the curl at a port nothing served. */
export function eventsEndpointUrl(): string {
  return `${import.meta.env.VITE_API_URL || window.location.origin}/api/v1/events`;
}

export function sendEventCurl({ payload, apiKey = 'YOUR_API_KEY' }: { payload: string; apiKey?: string }): string {
  return [
    `curl -X POST ${eventsEndpointUrl()} \\`,
    `  -H "X-API-Key: ${apiKey}" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -d '${payload}'`,
  ].join('\n');
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Chromium resolves Ukraine to Europe/Kiev, renamed Europe/Kyiv in tzdata 2022b. */
const RENAMED_ZONES: Record<string, string> = {
  'Europe/Kiev': 'Europe/Kyiv',
};

export function canonicalTimezone(tz: string): string {
  return RENAMED_ZONES[tz] ?? tz;
}

/** Must match the header the API's verifier for that provider reads. */
export const PROVIDER_SIGNATURE_HEADERS: Partial<Record<ProviderType, string>> = {
  GITHUB: 'X-Hub-Signature-256',
  STRIPE: 'Stripe-Signature',
  SHOPIFY: 'X-Shopify-Hmac-SHA256',
  SLACK: 'X-Slack-Signature',
  GITLAB: 'X-Gitlab-Token',
  TWILIO: 'X-Twilio-Signature',
  SQUARE: 'x-square-hmacsha256-signature',
  SENDGRID: 'X-Twilio-Email-Event-Webhook-Signature',
  HUBSPOT: 'X-HubSpot-Signature-v3',
  // No Adyen entry: its payments webhook signs inside the body, not a header.
};
