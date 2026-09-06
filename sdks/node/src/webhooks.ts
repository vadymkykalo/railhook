import * as crypto from 'crypto';
import { WebhookEvent } from './types';
import { RailhookError } from './errors';

const SIGNATURE_HEADER = 'x-signature';
const TIMESTAMP_HEADER = 'x-timestamp';
const EVENT_ID_HEADER = 'x-event-id';
const DELIVERY_ID_HEADER = 'x-delivery-id';

const DEFAULT_TOLERANCE = 300000; // 5 minutes in milliseconds

export interface WebhookHeaders {
  'x-signature'?: string;
  'x-timestamp'?: string;
  'x-event-id'?: string;
  'x-delivery-id'?: string;
  [key: string]: string | undefined;
}

export interface VerifyOptions {
  tolerance?: number;
}

/**
 * Verifies the webhook signature using HMAC-SHA256.
 *
 * The header is `t=<unix-ms>,v1=<hex>`, and it may carry **more than one** `v1`.
 * After you rotate an endpoint's secret, Railhook signs each delivery with both
 * the new secret and the retired one for the endpoint's grace window (24 hours
 * by default), so you can deploy the new secret whenever you like instead of at
 * the instant you press rotate. The delivery is authentic if *any* `v1` matches,
 * which is what this checks.
 *
 * @param payload - Raw request body as string
 * @param signature - X-Signature header value (format: t=timestamp,v1=signature[,v1=...])
 * @param secret - Endpoint webhook secret
 * @param options - Verification options
 * @returns true if signature is valid
 * @throws RailhookError if signature is invalid
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
    // Collected, not overwritten: a header sent during a secret rotation carries
    // one v1 per valid secret, and keeping only the last would reject whichever
    // of the pair you are currently holding.
    if (key === 'v1' && value) signatures.push(value.trim());
  }

  if (!timestamp || signatures.length === 0) {
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
  // Every candidate is compared — no early exit — so the time taken does not
  // depend on which position matched.
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

/** Tolerance for the Standard Webhooks headers, whose timestamp is in **seconds**. */
const DEFAULT_STANDARD_TOLERANCE_SECONDS = 300;

/**
 * Verifies the [Standard Webhooks](https://www.standardwebhooks.com) headers.
 *
 * Endpoints receive both header sets by default (`signatureScheme: 'BOTH'`), so use
 * whichever you prefer — this one if you would rather your verification match what other
 * providers send, `verifySignature` if you are already verifying `X-Signature`.
 *
 * Two things differ from Railhook's own scheme beyond the header names: the message id is
 * part of what is signed, and the digest is base64 rather than hex. Rotation works the same
 * way — during the grace window the header carries a space-separated signature per valid
 * secret, and any one matching is enough.
 *
 * @param payload - Raw request body as string
 * @param headers - Request headers, including the three `webhook-*` ones
 * @param secret - The endpoint's `standardWebhooksSecret` (`whsec_…`), not the raw secret.
 *                 A plain secret is accepted too and used as-is.
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

  const id = headers[STANDARD_ID_HEADER] || headers['Webhook-Id'];
  const timestamp = headers[STANDARD_TIMESTAMP_HEADER] || headers['Webhook-Timestamp'];
  const signature = headers[STANDARD_SIGNATURE_HEADER] || headers['Webhook-Signature'];

  if (!id || !timestamp || !signature) {
    throw new RailhookError(
      'Missing webhook-id, webhook-timestamp or webhook-signature header',
      400,
      'invalid_signature'
    );
  }

  const timestampSeconds = parseInt(timestamp, 10);
  if (Number.isNaN(timestampSeconds)) {
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

  // `whsec_<base64>` is the conventional form and is what the endpoint's
  // standardWebhooksSecret gives you; the base64 body decodes to the key bytes. Anything
  // else is taken literally, so a raw secret still works.
  const key = secret.startsWith('whsec_')
    ? Buffer.from(secret.slice('whsec_'.length), 'base64')
    : Buffer.from(secret, 'utf8');

  const expected = crypto
    .createHmac('sha256', key)
    .update(`${id}.${timestampSeconds}.${payload}`)
    .digest('base64');
  const expectedBuffer = Buffer.from(expected);

  // Space-separated, one per valid secret during a rotation window. Every candidate is
  // compared with no early exit, so the time taken does not reveal which one matched.
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
 * Constructs a webhook event from the request.
 *
 * What Railhook actually PUTs on the wire is the event's **payload**, not an
 * envelope: a `client.events.send({ type: 'order.completed', data: {...} })`
 * arrives at your endpoint as the `data` object alone, with the identifiers
 * carried in headers (`X-Event-Id`, `X-Delivery-Id`, `X-Timestamp`,
 * `X-Sequence-Number`). So:
 *
 * - `eventId` / `deliveryId` / `timestamp` come from the headers and are
 *   always populated for a real delivery.
 * - `data` is the parsed body.
 * - `type` is only populated when the body happens to carry a `type` key —
 *   which for a default subscription it does not. Route on the payload, or
 *   configure the subscription's `payloadTemplate` to wrap the event so that
 *   `type` is part of the body.
 *
 * @param payload - Raw request body as string
 * @param headers - Request headers
 * @param secret - Endpoint webhook secret
 * @param options - Verification options
 * @returns Parsed and verified webhook event
 */
export function constructEvent(
  payload: string,
  headers: WebhookHeaders,
  secret: string,
  options: VerifyOptions = {}
): WebhookEvent {
  const signature = headers[SIGNATURE_HEADER] || headers['X-Signature'];
  const timestamp = headers[TIMESTAMP_HEADER] || headers['X-Timestamp'];
  const eventId = headers[EVENT_ID_HEADER] || headers['X-Event-Id'];
  const deliveryId = headers[DELIVERY_ID_HEADER] || headers['X-Delivery-Id'];

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

/**
 * Generates a signature for testing purposes
 * @param payload - Request body as string
 * @param secret - Webhook secret
 * @param timestamp - Optional timestamp (defaults to now)
 * @returns Signature string in format t=timestamp,v1=signature
 */
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
