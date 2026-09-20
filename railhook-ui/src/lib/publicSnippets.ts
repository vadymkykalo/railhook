import type { ProviderType } from '../types/api.types';

/**
 * Text a person copies out of the product and runs somewhere else.
 *
 * Each piece used to be written inline where it was shown, and QA on a fresh cloud account found
 * `https://your-api.com` and `https://your-domain.com` in two curl snippets and a CLI command the
 * CLI rejects. Built here, from the deployment's own origin, they stay runnable.
 */

/** `railhook listen <port>`: the port is positional; `-p` is `--project` (ListenCommand). */
export const CLI_LISTEN_EXAMPLE = 'railhook listen 3000';

/**
 * The events endpoint of the API this dashboard itself talks to — the same origin `http.ts` sends
 * every request to. Not the site URL: that is what pages name about themselves (APP_BASE_URL), and
 * a stale one pointed the copied curl at a port nothing served.
 */
export function eventsEndpointUrl(): string {
  return `${import.meta.env.VITE_API_URL || window.location.origin}/api/v1/events`;
}

/** A `curl` that sends an event to this deployment. The key stays a placeholder. */
export function sendEventCurl({ payload, apiKey = 'YOUR_API_KEY' }: { payload: string; apiKey?: string }): string {
  return [
    `curl -X POST ${eventsEndpointUrl()} \\`,
    `  -H "X-API-Key: ${apiKey}" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -d '${payload}'`,
  ].join('\n');
}

/** Bytes a person can read: exact under 1 KB, one decimal above. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * The current IANA name for a zone a browser may still report by its old one. Chromium resolves
 * Ukraine to `Europe/Kiev`, renamed `Europe/Kyiv` in tzdata 2022b.
 */
const RENAMED_ZONES: Record<string, string> = {
  'Europe/Kiev': 'Europe/Kyiv',
};

export function canonicalTimezone(tz: string): string {
  return RENAMED_ZONES[tz] ?? tz;
}

/**
 * The header each built-in provider signs its webhooks in — the one the API's verifier for that
 * provider reads (service/verification/*Verifier.java). A generic source names its own header.
 */
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
  // Adyen is the one with no entry on purpose: its standard payments webhook carries the
  // signature inside the body, in each notification item's additionalData.hmacSignature, and
  // naming a header here would tell a person to look for one that never arrives. Its
  // Management and Balance Platform webhooks do sign in an `hmacsignature` header, so the
  // source accepts either — which is a sentence for the docs, not a cell in a lookup table.
};
