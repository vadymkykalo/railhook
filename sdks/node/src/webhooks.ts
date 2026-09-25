import * as crypto from 'crypto';
import { WebhookEvent } from './types';
import { RailhookError } from './errors';

const SIGNATURE_HEADER = 'x-signature';
const TIMESTAMP_HEADER = 'x-timestamp';
const EVENT_ID_HEADER = 'x-event-id';
const DELIVERY_ID_HEADER = 'x-delivery-id';

const DEFAULT_TOLERANCE = 300000; // ms

/** Takes Node's `IncomingHttpHeaders` as-is; of a repeated header the first value is used. */
export interface WebhookHeaders {
  'x-signature'?: string | string[];
  'x-timestamp'?: string | string[];
  'x-event-id'?: string | string[];
  'x-delivery-id'?: string | string[];
  [key: string]: string | string[] | undefined;
}

function header(headers: WebhookHeaders, lower: string, canonical: string): string | undefined {
  const value = headers[lower] || headers[canonical];
  return Array.isArray(value) ? value[0] : value;
}

export interface VerifyOptions {
  tolerance?: number;
}

/**
 * Verifies an `X-Signature` header (`t=<unix-ms>,v1=<hex>[,v1=...]`); during a secret rotation
 * any one `v1` matching is enough.
 *
 * @param payload - Raw request body
 * @param signature - X-Signature header value
 * @param secret - Endpoint webhook secret
 * @param options - `tolerance` in milliseconds (default 5 minutes)
 * @returns true if the signature is valid
 * @throws RailhookError if it is not
 */
export function verifySignature(
  payload: string,
  signature: string,
  secret: string,
  options: VerifyOptions = {}
): boolean {
  const tolerance = options.tolerance ?? DEFAULT_TOLERANCE;

  if (!signature) {
    throw new RailhookError('Missing signature header', 400, 'invalid_signature');
  }

  const parts = signature.split(',');
  let timestamp: string | undefined;
  const signatures: string[] = [];

  for (const part of parts) {
    const [key, value] = part.split('=');
    if (key === 't') timestamp = value?.trim();
    // Collected, not overwritten: during a rotation keeping only the last v1 would reject
    // whichever secret you currently hold.
    if (key === 'v1' && value) signatures.push(value.trim());
  }

  if (!timestamp || signatures.length === 0) {
    throw new RailhookError(
      'Invalid signature format. Expected: t=timestamp,v1=signature',
      400,
      'invalid_signature'
    );
  }

  // parseInt('abc') is NaN, and a NaN comparison is false: a non-numeric t used to skip the
  // replay window entirely.
  if (!/^\d+$/.test(timestamp)) {
    throw new RailhookError(
      'Invalid signature format. Expected: t=timestamp,v1=signature',
      400,
      'invalid_signature'
    );
  }

  const timestampMs = parseInt(timestamp, 10);
  const now = Date.now();

  if (Math.abs(now - timestampMs) > tolerance) {
    throw new RailhookError(
      'Webhook timestamp is outside tolerance window',
      400,
      'timestamp_expired'
    );
  }

  const signedPayload = `${timestamp}.${payload}`;
  const expectedSignature = crypto
    .createHmac('sha256', secret)
    .update(signedPayload)
    .digest('hex');

  const expectedBuffer = Buffer.from(expectedSignature);
  // No early exit, so timing does not reveal which candidate matched.
  let matched = false;
  for (const candidate of signatures) {
    const sigBuffer = Buffer.from(candidate);
    if (sigBuffer.length === expectedBuffer.length && crypto.timingSafeEqual(sigBuffer, expectedBuffer)) {
      matched = true;
    }
  }

  if (!matched) {
    throw new RailhookError('Invalid signature', 400, 'invalid_signature');
  }

  return true;
}

const STANDARD_ID_HEADER = 'webhook-id';
const STANDARD_TIMESTAMP_HEADER = 'webhook-timestamp';
const STANDARD_SIGNATURE_HEADER = 'webhook-signature';

/** Standard Webhooks timestamps are in seconds, not milliseconds. */
const DEFAULT_STANDARD_TOLERANCE_SECONDS = 300;

/**
 * Verifies the Standard Webhooks headers (`webhook-id`, `webhook-timestamp`,
 * `webhook-signature`). During a rotation's grace window any one matching signature is enough.
 *
 * @param payload - Raw request body
 * @param headers - Request headers
 * @param secret - The endpoint's `standardWebhooksSecret` (`whsec_…`); a plain secret is used as-is
 * @param options - `toleranceSeconds` (default 300)
 * @returns true if the signature is valid
 * @throws RailhookError if it is not
 */
export function verifyStandardWebhook(
  payload: string,
  headers: WebhookHeaders,
  secret: string,
  options: { toleranceSeconds?: number } = {}
): boolean {
  const tolerance = options.toleranceSeconds ?? DEFAULT_STANDARD_TOLERANCE_SECONDS;

  const id = header(headers, STANDARD_ID_HEADER, 'Webhook-Id');
  const timestamp = header(headers, STANDARD_TIMESTAMP_HEADER, 'Webhook-Timestamp');
  const signature = header(headers, STANDARD_SIGNATURE_HEADER, 'Webhook-Signature');

  if (!id || !timestamp || !signature) {
    throw new RailhookError(
      'Missing webhook-id, webhook-timestamp or webhook-signature header',
      400,
      'invalid_signature'
    );
  }

  // Digits only: parseInt stops at the first non-digit, so `<ts>xyz` verified as `<ts>`.
  const timestampSeconds = parseInt(timestamp.trim(), 10);
  if (!/^\d+$/.test(timestamp.trim())) {
    throw new RailhookError('Invalid webhook-timestamp header', 400, 'invalid_signature');
  }

  const nowSeconds = Math.floor(Date.now() / 1000);
  if (Math.abs(nowSeconds - timestampSeconds) > tolerance) {
    throw new RailhookError(
      'Webhook timestamp is outside tolerance window',
      400,
      'timestamp_expired'
    );
  }

  const key = secret.startsWith('whsec_')
    ? Buffer.from(secret.slice('whsec_'.length), 'base64')
    : Buffer.from(secret, 'utf8');

  const expected = crypto
    .createHmac('sha256', key)
    .update(`${id}.${timestampSeconds}.${payload}`)
    .digest('base64');
  const expectedBuffer = Buffer.from(expected);

  // No early exit, so timing does not reveal which candidate matched.
  let matched = false;
  for (const part of signature.trim().split(/\s+/)) {
    const comma = part.indexOf(',');
    if (comma < 1 || part.slice(0, comma) !== 'v1') continue;
    const candidate = Buffer.from(part.slice(comma + 1));
    if (candidate.length === expectedBuffer.length && crypto.timingSafeEqual(candidate, expectedBuffer)) {
      matched = true;
    }
  }

  if (!matched) {
    throw new RailhookError('Invalid signature', 400, 'invalid_signature');
  }

  return true;
}

/**
 * Verifies the request and parses it into an event. `type` is set only when the body carries a
 * `type` key; ids come from the headers.
 *
 * @param payload - Raw request body
 * @param headers - Request headers
 * @param secret - Endpoint webhook secret
 * @param options - Verification options
 * @throws RailhookError if the signature or the JSON is invalid
 */
export function constructEvent(
  payload: string,
  headers: WebhookHeaders,
  secret: string,
  options: VerifyOptions = {}
): WebhookEvent {
  const signature = header(headers, SIGNATURE_HEADER, 'X-Signature');
  const timestamp = header(headers, TIMESTAMP_HEADER, 'X-Timestamp');
  const eventId = header(headers, EVENT_ID_HEADER, 'X-Event-Id');
  const deliveryId = header(headers, DELIVERY_ID_HEADER, 'X-Delivery-Id');

  if (!signature) {
    throw new RailhookError('Missing X-Signature header', 400, 'missing_header');
  }

  verifySignature(payload, signature, secret, options);

  let data: Record<string, unknown>;
  try {
    data = JSON.parse(payload);
  } catch {
    throw new RailhookError('Invalid JSON payload', 400, 'invalid_payload');
  }

  return {
    eventId: eventId || '',
    deliveryId: deliveryId || '',
    timestamp: timestamp ? parseInt(timestamp, 10) : Date.now(),
    type: (data.type as string) || '',
    data: (data.data as Record<string, unknown>) || data,
  };
}

/** Builds an `X-Signature` value (`t=<ms>,v1=<hex>`) for tests; `timestamp` defaults to now. */
export function generateSignature(
  payload: string,
  secret: string,
  timestamp?: number
): string {
  const ts = timestamp ?? Date.now();
  const signedPayload = `${ts}.${payload}`;
  const signature = crypto
    .createHmac('sha256', secret)
    .update(signedPayload)
    .digest('hex');

  return `t=${ts},v1=${signature}`;
}
