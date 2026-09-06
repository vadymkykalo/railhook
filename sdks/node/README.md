# @railhook/node

Official Node.js SDK for [Railhook](https://github.com/vadymkykalo/railhook).

**Zero runtime dependencies.** This SDK talks to the API using Node's built-in
`node:https` module — no third-party HTTP client, no transitive dependency
tree to audit. `npm ls --prod` on this package prints nothing.

## Scope

This SDK covers the **send + verify** surface of the API: Events, Endpoints,
Subscriptions, Deliveries, Incoming Sources, Incoming Events, and webhook
signature verification — 7 of the platform's 35 API controllers. It
does **not** wrap Transformations, Rules, Workflows, Schemas, DLQ, Analytics,
Usage, Alerts, Incidents, PII rules, Audit Log, Tunnels, API keys, Members,
or Projects. Those are dashboard/API-only today; use the [Generic
Requests](#generic-requests) escape hatch below to call them directly if you
need them before the SDK grows to cover them.

## Installation

```bash
npm install @railhook/node
```

## Quick Start

```typescript
import { Railhook } from '@railhook/node';

const client = new Railhook({
  apiKey: process.env.RAILHOOK_API_KEY, // e.g. 'Kz1uAIM8VeJUQN7yGSYCst64WxNLabBHfOYbrPlJ1yk'
  baseUrl: 'http://localhost:8080', // optional, defaults to localhost
});

// Send an event
const event = await client.events.send({
  type: 'order.completed',
  data: {
    orderId: 'ord_12345',
    amount: 99.99,
    currency: 'USD',
  },
});

console.log(`Event created: ${event.eventId}`);
console.log(`Deliveries created: ${event.deliveriesCreated}`);
```

## API Reference

### Events

```typescript
// Send event with idempotency key
const event = await client.events.send(
  { type: 'order.completed', data: { orderId: '123' } },
  'unique-idempotency-key'
);
```

### Endpoints

```typescript
// Create endpoint
const endpoint = await client.endpoints.create(projectId, {
  url: 'https://api.example.com/webhooks',
  description: 'Production webhooks',
  enabled: true,
});

// List endpoints — the API paginates this one, so the endpoints are in .content
const page = await client.endpoints.list(projectId, { page: 0, size: 20 });
for (const endpoint of page.content) {
  console.log(endpoint.url);
}

// Update endpoint
await client.endpoints.update(projectId, endpointId, {
  enabled: false,
});

// Delete endpoint
await client.endpoints.delete(projectId, endpointId);

// Rotate secret
const updated = await client.endpoints.rotateSecret(projectId, endpointId);
console.log(`New secret: ${updated.secret}`);

// Test endpoint connectivity
const result = await client.endpoints.test(projectId, endpointId);
console.log(`Test ${result.success ? 'passed' : 'failed'}: ${result.latencyMs}ms`);
console.log(`${result.httpStatusCode} — ${result.message}`);
```

### Subscriptions

```typescript
// Subscribe endpoint to an event type
const subscription = await client.subscriptions.create(projectId, {
  endpointId: endpoint.id,
  eventType: 'order.completed',
  enabled: true,
});

// List subscriptions — a bare array; unlike endpoints, this one is not paginated
const subscriptions = await client.subscriptions.list(projectId);

// Update subscription
await client.subscriptions.update(projectId, subscriptionId, {
  eventType: 'order.shipped',
  enabled: true,
});

// Delete subscription
await client.subscriptions.delete(projectId, subscriptionId);
```

### Deliveries

```typescript
// List deliveries with filters
const deliveries = await client.deliveries.list(projectId, {
  status: 'FAILED',
  page: 0,
  size: 20,
});

console.log(`Total failed: ${deliveries.totalElements}`);

// Get delivery attempts
const attempts = await client.deliveries.getAttempts(deliveryId);
for (const attempt of attempts) {
  console.log(`Attempt ${attempt.attemptNumber}: ${attempt.httpStatusCode} (${attempt.durationMs}ms)`);
}

// Replay failed delivery
await client.deliveries.replay(deliveryId);
```

## Incoming Webhooks

Receive, validate, and forward webhooks from third-party providers (Stripe, GitHub, Twilio, etc.).

### Incoming Sources

```typescript
// Create an incoming source with HMAC verification
const source = await client.incomingSources.create(projectId, {
  name: 'Stripe Webhooks',
  slug: 'stripe',
  providerType: 'STRIPE',
  verificationMode: 'HMAC_GENERIC',
  hmacSecret: 'whsec_...',
  hmacHeaderName: 'Stripe-Signature',
});

console.log(`Ingress URL: ${source.ingressUrl}`);

// List sources
const sources = await client.incomingSources.list(projectId);

// Update source
await client.incomingSources.update(projectId, sourceId, {
  name: 'Stripe Production',
  rateLimitPerSecond: 100,
});

// Delete source
await client.incomingSources.delete(projectId, sourceId);
```

### Incoming Destinations

```typescript
// Add a forwarding destination
const dest = await client.incomingSources.createDestination(projectId, sourceId, {
  url: 'https://your-api.com/webhooks/stripe',
  enabled: true,
  maxAttempts: 5,
  timeoutSeconds: 30,
});

// List destinations
const dests = await client.incomingSources.listDestinations(projectId, sourceId);

// Update destination
await client.incomingSources.updateDestination(projectId, sourceId, destId, {
  enabled: false,
});

// Delete destination
await client.incomingSources.deleteDestination(projectId, sourceId, destId);
```

### Incoming Events

```typescript
// List incoming events (with optional source filter)
const events = await client.incomingEvents.list(projectId, {
  sourceId: source.id,
  page: 0,
  size: 20,
});

// Get event details
const event = await client.incomingEvents.get(projectId, eventId);

// Get forward attempts for an event
const attempts = await client.incomingEvents.getAttempts(projectId, eventId);

// Replay event to all destinations
const result = await client.incomingEvents.replay(projectId, eventId);
console.log(`Replayed to ${result.destinationsCount} destinations`);
```

## Webhook Signature Verification

Verify incoming webhooks in your endpoint:

```typescript
import { verifySignature, constructEvent } from '@railhook/node';

app.post('/webhooks', (req, res) => {
  const payload = req.body; // raw body string
  const signature = req.headers['x-signature'];
  const secret = process.env.WEBHOOK_SECRET;

  try {
    // Option 1: Just verify
    verifySignature(payload, signature, secret);

    // Option 2: Verify and parse
    const event = constructEvent(payload, req.headers, secret);

    // event.data is the parsed body; event.eventId / event.deliveryId /
    // event.timestamp come from the X-Event-Id / X-Delivery-Id / X-Timestamp
    // headers. See "What lands on your endpoint" below for event.type.
    console.log(`Delivery ${event.deliveryId} of event ${event.eventId}:`, event.data);
    handleOrderCompleted(event.data);

    res.status(200).send('OK');
  } catch (err) {
    console.error('Webhook verification failed:', err.message);
    res.status(400).send('Invalid signature');
  }
});
```

### What lands on your endpoint

Railhook PUTs the event's **payload** on the wire, not an envelope. This:

```typescript
await client.events.send({ type: 'order.completed', data: { orderId: 'ord_1' } });
```

arrives at your endpoint as the `data` object alone —

```http
POST /webhooks HTTP/1.1
Content-Type: application/json
X-Signature: t=1738000000000,v1=<hex hmac-sha256>
X-Timestamp: 1738000000000
X-Event-Id: 6f0e…
X-Delivery-Id: 91ab…
X-Sequence-Number: 0
Idempotency-Key: 6f0e…-<endpoint-id>

{"orderId":"ord_1"}
```

So `constructEvent` fills `eventId`, `deliveryId` and `timestamp` from the
headers and `data` from the body, but **`type` is empty**: the event type is
not on the wire for a default subscription. Route on the payload, on the
endpoint you registered, or set the subscription's `payloadTemplate` to wrap
the event so `type` becomes part of the body.

The signature is computed over `` `${timestamp}.${rawBody}` `` with HMAC-SHA256
and the endpoint secret, and the server rejects timestamps more than **300
seconds** old — verify against the *raw* body bytes, before any JSON parse and
re-serialize.

### Express.js Example

```typescript
import express from 'express';
import { constructEvent } from '@railhook/node';

const app = express();

// Important: Use raw body for signature verification
app.post('/webhooks', express.raw({ type: 'application/json' }), (req, res) => {
  const event = constructEvent(
    req.body.toString(),
    req.headers,
    process.env.WEBHOOK_SECRET
  );

  // Process event...
  res.sendStatus(200);
});
```

## Error Handling

```typescript
import { 
  RailhookError, 
  RateLimitError, 
  AuthenticationError,
  ValidationError 
} from '@railhook/node';

try {
  await client.events.send({ type: 'test', data: {} });
} catch (err) {
  if (err instanceof RateLimitError) {
    // retryAfter is milliseconds. err.rateLimitInfo.reset is the raw
    // X-RateLimit-Reset header, which the API sends in Unix *seconds*.
    console.log(`Rate limited. Retry after ${err.retryAfter}ms`);
    await sleep(err.retryAfter);
  } else if (err instanceof AuthenticationError) {
    console.error('Invalid API key');
  } else if (err instanceof ValidationError) {
    console.error('Validation failed:', err.fieldErrors);
  } else if (err instanceof RailhookError) {
    console.error(`Error ${err.status}: ${err.message}`);
  }
}
```

### Error Response Format

All API errors return a consistent JSON body:

```json
{
  "error": "error_code",
  "message": "Human-readable description",
  "status": 400,
  "fieldErrors": { "field": "reason" }
}
```

- **`error`** — machine-readable error code (`snake_case`), always present
- **`message`** — human-readable description, always present
- **`status`** — HTTP status code (integer), always present
- **`fieldErrors`** — field-level validation details (only present for `validation_error`)

### Error Codes Reference

| HTTP Status | `error` Code | SDK Exception | Description |
|---|---|---|---|
| 400 | `validation_error` | `ValidationError` | Invalid request parameters; see `fieldErrors` |
| 400 | `invalid_request` | `RailhookError` | Malformed or semantically invalid request |
| 401 | `unauthorized` | `AuthenticationError` | Missing or invalid API key / expired token |
| 403 | `forbidden` | `RailhookError` | Insufficient permissions for the action |
| 404 | `not_found` | `NotFoundError` | Requested resource does not exist |
| 413 | `payload_too_large` | `RailhookError` | Request body exceeds maximum allowed size |
| 422 | `unprocessable_entity` | `RailhookError` | Valid syntax but violates business rules |
| 429 | `rate_limit_exceeded` | `RateLimitError` | Too many requests; check `X-RateLimit-*` headers |
| 500 | `internal_error` | `RailhookError` | Unexpected server error |

## Generic Requests

As the API expands, you can call any endpoint directly without waiting for SDK updates:

```typescript
// GET
const schemas = await client.get<any[]>('/api/v1/projects/proj_123/schemas');

// POST with body and idempotency key
const result = await client.post('/api/v1/some/new/endpoint', { key: 'value' }, 'idempotency-key');

// PUT
await client.put('/api/v1/projects/proj_123/settings', { timezone: 'UTC' });

// PATCH
await client.patch('/api/v1/projects/proj_123/settings', { timezone: 'UTC' });

// DELETE
await client.delete('/api/v1/projects/proj_123/tags/old-tag');

// Fully custom request (any HTTP method)
const data = await client.request<any>('OPTIONS', '/api/v1/some/path');
```

All generic methods use the same authentication, error handling, and rate-limit logic as the built-in methods.

## Configuration

```typescript
const client = new Railhook({
  apiKey: process.env.RAILHOOK_API_KEY, // Required: Your project API key
  baseUrl: 'https://api.example.com', // Optional: API base URL (default: http://localhost:8080)
  timeout: 30000,              // Optional: Request timeout in ms (default: 30000)
});
```

### Timeouts and retries

`timeout` is a per-request socket timeout; hitting it rejects with
`RailhookError` (`code: 'timeout'`, `status: 0`). A connection-level failure
rejects the same way with `code: 'network_error'`.

**The client does not retry.** One SDK call is exactly one HTTP request — no
backoff, no idempotent replay. That is deliberate: `events.send` accepts an
`Idempotency-Key`, so a retry policy belongs to the caller who knows whether
reissuing the request is safe. What *is* retried is the delivery itself, by the
platform, on the subscription's `retryDelays` ladder.

## Authentication

Every request the client makes carries `X-API-Key: <your key>` — the project
API key, created in the dashboard or via
`POST /api/v1/projects/{projectId}/api-keys`. The SDK never sends a bearer
token and has no login surface: JWT-authenticated endpoints (auth, projects,
organizations, members, API keys) are not part of it. Bootstrapping a project
and a key is a one-time step you do with the dashboard, the CLI, or plain
HTTP.

## TypeScript Support

This SDK is written in TypeScript and includes full type definitions:

```typescript
import type { 
  Event, 
  EventResponse, 
  Endpoint, 
  Delivery,
  DeliveryStatus 
} from '@railhook/node';
```

## Development

### Running Tests

**Local (requires Node.js 16+):**
```bash
npm install
npm test
```

**Docker:**
```bash
docker run --rm -v $(pwd):/app -w /app node:20-alpine sh -c "npm install && npm test"
```

### Live-API smoke check

`npm test` stubs the transport, so it cannot see a renamed field. To drive the
SDK against a real instance:

```bash
make up                # from the repo root
npm run smoke:live     # SMOKE_API_BASE_URL overrides the target
```

It registers a throwaway org, walks endpoint → subscription → event →
deliveries → attempts → incoming, checks each error envelope, and verifies a
signature the running server itself produced. It is a script, not a test —
`jest.config.js` roots at `src/`, so `npm test` never collects it and still
passes with no backend.

### Building

```bash
npm run build
```

## License

MIT
