/**
 * Webhook signature checks for the public verifier at /tools/webhook-signature.
 *
 * Everything runs in the browser with Web Crypto: the secret and the payload a reader pastes are
 * never sent anywhere, which is the promise the page makes. The schemes match what Railhook's
 * own verifiers accept (the backend's StripeVerifier, GitHubVerifier, ShopifyVerifier,
 * SlackVerifier and StandardWebhookSignature), including the 300-second window for the schemes
 * that sign a timestamp.
 */

export type Provider = 'standard' | 'railhook' | 'stripe' | 'github' | 'shopify' | 'slack';

export interface ProviderScheme {
  id: Provider;
  /** The headers the reader pastes, in the order the form shows them. */
  headers: string[];
  /** The header that carries the signature itself. */
  signatureHeader: string;
  /** How far a signed timestamp may be from now, where the scheme signs one. */
  toleranceSeconds?: number;
}

const TOLERANCE_SECONDS = 300;

export const PROVIDERS: readonly ProviderScheme[] = [
  {
    id: 'standard',
    headers: ['webhook-id', 'webhook-timestamp', 'webhook-signature'],
    signatureHeader: 'webhook-signature',
    toleranceSeconds: TOLERANCE_SECONDS,
  },
  { id: 'stripe', headers: ['Stripe-Signature'], signatureHeader: 'Stripe-Signature', toleranceSeconds: TOLERANCE_SECONDS },
  { id: 'github', headers: ['X-Hub-Signature-256'], signatureHeader: 'X-Hub-Signature-256' },
  { id: 'shopify', headers: ['X-Shopify-Hmac-Sha256'], signatureHeader: 'X-Shopify-Hmac-Sha256' },
  {
    id: 'slack',
    headers: ['X-Slack-Signature', 'X-Slack-Request-Timestamp'],
    signatureHeader: 'X-Slack-Signature',
    toleranceSeconds: TOLERANCE_SECONDS,
  },
  { id: 'railhook', headers: ['X-Signature'], signatureHeader: 'X-Signature', toleranceSeconds: TOLERANCE_SECONDS },
];

export function schemeOf(provider: Provider): ProviderScheme {
  return PROVIDERS.find((p) => p.id === provider)!;
}

export interface TimestampCheck {
  /** The signed timestamp, in seconds. */
  value: number;
  /** How long before `now` it was signed; negative when it is in the future. */
  ageSeconds: number;
  toleranceSeconds: number;
  withinTolerance: boolean;
}

export type VerifyResult =
  | { status: 'incomplete' }
  | { status: 'malformed'; reason: 'secret' | 'header' | 'timestamp'; header?: string }
  | {
      status: 'valid' | 'invalid';
      /** The signature header as it should read for this payload and secret. */
      expected: string;
      timestamp?: TimestampCheck;
    };

export interface VerifyInput {
  provider: Provider;
  payload: string;
  secret: string;
  /** Header values by the names in the provider's `headers`. */
  headers: Record<string, string>;
  /** Milliseconds since the epoch; the clock, injectable for tests. */
  now?: number;
}

const encoder = new TextEncoder();

async function hmacSha256(key: Uint8Array, message: string): Promise<Uint8Array> {
  const cryptoKey = await crypto.subtle.importKey('raw', key as BufferSource, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', cryptoKey, encoder.encode(message) as BufferSource));
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  try {
    return Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
  } catch {
    return null;
  }
}

/**
 * A header's value, with its name taken off the front if the reader pasted the whole line
 * (`Stripe-Signature: t=…`), which is how it is usually copied out of a log.
 */
export function readHeaderValue(raw: string, name: string): string {
  const value = raw.trim();
  const prefix = `${name.toLowerCase()}:`;
  return value.toLowerCase().startsWith(prefix) ? value.slice(prefix.length).trim() : value;
}

/** `t=…,v1=…,v1=…` into its timestamp and every v1, as Stripe and Railhook both send it. */
function readTimestampedHeader(value: string): { t?: string; signatures: string[] } {
  let t: string | undefined;
  const signatures: string[] = [];
  for (const part of value.split(',')) {
    const [key, ...rest] = part.trim().split('=');
    const v = rest.join('=');
    if (key === 't') t = v;
    else if (key === 'v1' && v) signatures.push(v);
  }
  return { t, signatures };
}

function timestampCheck(seconds: number, now: number): TimestampCheck {
  const ageSeconds = Math.round(now / 1000 - seconds);
  return {
    value: seconds,
    ageSeconds,
    toleranceSeconds: TOLERANCE_SECONDS,
    withinTolerance: Math.abs(ageSeconds) <= TOLERANCE_SECONDS,
  };
}

const INTEGER = /^\d+$/;

/** The key a Standard Webhooks secret stands for: base64 after `whsec_`, or the text as given. */
function standardKey(secret: string): Uint8Array | null {
  return secret.startsWith('whsec_') ? fromBase64(secret.slice('whsec_'.length)) : encoder.encode(secret);
}

export async function verifySignature({ provider, payload, secret, headers, now = Date.now() }: VerifyInput): Promise<VerifyResult> {
  const scheme = schemeOf(provider);
  const value = (name: string) => readHeaderValue(headers[name] ?? '', name);
  if (!secret || scheme.headers.some((name) => !value(name))) return { status: 'incomplete' };
  const utf8Key = encoder.encode(secret);

  switch (provider) {
    case 'standard': {
      const key = standardKey(secret);
      if (!key || key.length === 0) return { status: 'malformed', reason: 'secret' };
      const id = value('webhook-id');
      const ts = value('webhook-timestamp');
      if (!INTEGER.test(ts)) return { status: 'malformed', reason: 'timestamp', header: 'webhook-timestamp' };
      const candidates = value('webhook-signature')
        .split(/\s+/)
        .filter((entry) => entry.startsWith('v1,'))
        .map((entry) => entry.slice(3));
      if (candidates.length === 0) return { status: 'malformed', reason: 'header', header: 'webhook-signature' };
      const expected = toBase64(await hmacSha256(key, `${id}.${ts}.${payload}`));
      return {
        status: candidates.includes(expected) ? 'valid' : 'invalid',
        expected: `v1,${expected}`,
        timestamp: timestampCheck(Number(ts), now),
      };
    }
    case 'stripe':
    case 'railhook': {
      const header = scheme.signatureHeader;
      const { t, signatures } = readTimestampedHeader(value(header));
      if (!t || signatures.length === 0) return { status: 'malformed', reason: 'header', header };
      if (!INTEGER.test(t)) return { status: 'malformed', reason: 'timestamp', header };
      const expected = toHex(await hmacSha256(utf8Key, `${t}.${payload}`));
      // Stripe's t is in seconds, Railhook's in milliseconds.
      const seconds = provider === 'stripe' ? Number(t) : Number(t) / 1000;
      return {
        status: signatures.includes(expected) ? 'valid' : 'invalid',
        expected: `t=${t},v1=${expected}`,
        timestamp: timestampCheck(seconds, now),
      };
    }
    case 'github': {
      const given = value('X-Hub-Signature-256');
      if (!given.startsWith('sha256=')) return { status: 'malformed', reason: 'header', header: 'X-Hub-Signature-256' };
      const expected = `sha256=${toHex(await hmacSha256(utf8Key, payload))}`;
      return { status: given === expected ? 'valid' : 'invalid', expected };
    }
    case 'shopify': {
      const expected = toBase64(await hmacSha256(utf8Key, payload));
      return { status: value('X-Shopify-Hmac-Sha256') === expected ? 'valid' : 'invalid', expected };
    }
    case 'slack': {
      const given = value('X-Slack-Signature');
      const ts = value('X-Slack-Request-Timestamp');
      if (!given.startsWith('v0=')) return { status: 'malformed', reason: 'header', header: 'X-Slack-Signature' };
      if (!INTEGER.test(ts)) return { status: 'malformed', reason: 'timestamp', header: 'X-Slack-Request-Timestamp' };
      const expected = `v0=${toHex(await hmacSha256(utf8Key, `v0:${ts}:${payload}`))}`;
      return { status: given === expected ? 'valid' : 'invalid', expected, timestamp: timestampCheck(Number(ts), now) };
    }
  }
}

/** Headers that sign `payload` with `secret` now: the page's "try an example". */
export async function exampleHeaders(provider: Provider, payload: string, secret: string, now = Date.now()): Promise<Record<string, string>> {
  const seconds = String(Math.floor(now / 1000));
  const utf8Key = encoder.encode(secret);
  switch (provider) {
    case 'standard': {
      const id = 'msg_2mZqN8vQ4kD7xYp1';
      const key = standardKey(secret) ?? utf8Key;
      const signature = toBase64(await hmacSha256(key, `${id}.${seconds}.${payload}`));
      return { 'webhook-id': id, 'webhook-timestamp': seconds, 'webhook-signature': `v1,${signature}` };
    }
    case 'stripe':
      return { 'Stripe-Signature': `t=${seconds},v1=${toHex(await hmacSha256(utf8Key, `${seconds}.${payload}`))}` };
    case 'railhook': {
      const t = String(Math.floor(now));
      return { 'X-Signature': `t=${t},v1=${toHex(await hmacSha256(utf8Key, `${t}.${payload}`))}` };
    }
    case 'github':
      return { 'X-Hub-Signature-256': `sha256=${toHex(await hmacSha256(utf8Key, payload))}` };
    case 'shopify':
      return { 'X-Shopify-Hmac-Sha256': toBase64(await hmacSha256(utf8Key, payload)) };
    case 'slack':
      return {
        'X-Slack-Signature': `v0=${toHex(await hmacSha256(utf8Key, `v0:${seconds}:${payload}`))}`,
        'X-Slack-Request-Timestamp': seconds,
      };
  }
}
