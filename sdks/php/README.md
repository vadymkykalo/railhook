# railhook/php

Official PHP SDK for [Railhook](https://github.com/vadymkykalo/railhook).

> The Packagist package is `railhook/php` and the PHP namespace is
> `Railhook\`. Published as `webhook-platform/php` with the namespace
> `Hookflow\` before 2.12.0; that package is marked abandoned in favour of
> this one.

**Scope.** This SDK covers Events, Endpoints, Subscriptions, Deliveries,
Incoming Sources, Incoming Events, and webhook signature verification —
7 of the platform's 35 API controllers. It does not wrap
Transformations, Rules, Workflows, Schemas, DLQ, Analytics, Usage, Alerts,
Incidents, PII rules, Audit Log, Tunnels, API keys, Members, or Projects —
use the [Generic Requests](#generic-requests) helpers for those until the
SDK grows to cover them.

## Requirements

- PHP 8.1+
- ext-json
- ext-curl

## Installation

```bash
composer require railhook/php
```

## Quick Start

```php
<?php

use Railhook\Railhook;

$client = new Railhook(
    apiKey: getenv('RAILHOOK_API_KEY'), // e.g. 'Kz1uAIM8VeJUQN7yGSYCst64WxNLabBHfOYbrPlJ1yk'
    baseUrl: 'http://localhost:8080' // optional
);

// Send an event
$event = $client->events->send(
    type: 'order.completed',
    data: [
        'orderId' => 'ord_12345',
        'amount' => 99.99,
        'currency' => 'USD',
    ]
);

echo "Event created: {$event['eventId']}\n";
echo "Deliveries created: {$event['deliveriesCreated']}\n";
```

## API Reference

### Events

```php
// Send event with idempotency key
$event = $client->events->send(
    type: 'order.completed',
    data: ['orderId' => '123'],
    idempotencyKey: 'unique-key'
);
```

### Endpoints

```php
// Create endpoint
$endpoint = $client->endpoints->create($projectId, [
    'url' => 'https://api.example.com/webhooks',
    'description' => 'Production webhooks',
    'enabled' => true,
]);

// List endpoints — the API paginates this one, so the endpoints are in ['content']
$page = $client->endpoints->list($projectId);
foreach ($page['content'] as $endpoint) {
    echo "{$endpoint['url']}\n";
}

// Update endpoint
$client->endpoints->update($projectId, $endpointId, [
    'enabled' => false,
]);

// Delete endpoint
$client->endpoints->delete($projectId, $endpointId);

// Rotate secret
$updated = $client->endpoints->rotateSecret($projectId, $endpointId);
echo "New secret: {$updated['secret']}\n";

// Test endpoint connectivity
$result = $client->endpoints->test($projectId, $endpointId);
$status = $result['success'] ? 'passed' : 'failed';
echo "Test {$status}: {$result['latencyMs']}ms\n";
echo "{$result['httpStatusCode']} — {$result['message']}\n";
```

### Subscriptions

```php
// Subscribe endpoint to an event type
$subscription = $client->subscriptions->create($projectId, [
    'endpointId' => $endpoint['id'],
    'eventType' => 'order.completed',
    'enabled' => true,
]);

// List subscriptions — a bare array; unlike endpoints, this one is not paginated
$subscriptions = $client->subscriptions->list($projectId);

// Update subscription
$client->subscriptions->update($projectId, $subscriptionId, [
    'eventType' => 'order.shipped',
    'enabled' => true,
]);

// Delete subscription
$client->subscriptions->delete($projectId, $subscriptionId);
```

### Deliveries

```php
// List deliveries with filters
$deliveries = $client->deliveries->list($projectId, [
    'status' => 'FAILED',
    'page' => 0,
    'size' => 20,
]);

echo "Total failed: {$deliveries['totalElements']}\n";

// Get delivery attempts
$attempts = $client->deliveries->getAttempts($deliveryId);
foreach ($attempts as $attempt) {
    echo "Attempt {$attempt['attemptNumber']}: {$attempt['httpStatusCode']} ({$attempt['durationMs']}ms)\n";
}

// Replay failed delivery
$client->deliveries->replay($deliveryId);
```

## Incoming Webhooks

Receive, validate, and forward webhooks from third-party providers (Stripe, GitHub, Twilio, etc.).

### Incoming Sources

```php
// Create an incoming source with HMAC verification
$source = $client->incomingSources->create($projectId, [
    'name' => 'Stripe Webhooks',
    'slug' => 'stripe',
    'providerType' => 'STRIPE',
    'verificationMode' => 'HMAC_GENERIC',
    'hmacSecret' => 'whsec_...',
    'hmacHeaderName' => 'Stripe-Signature',
]);

echo "Ingress URL: {$source['ingressUrl']}\n";

// List sources
$sources = $client->incomingSources->list($projectId);

// Update source
$client->incomingSources->update($projectId, $sourceId, [
    'name' => 'Stripe Production',
    'rateLimitPerSecond' => 100,
]);

// Delete source
$client->incomingSources->delete($projectId, $sourceId);
```

### Incoming Destinations

```php
// Add a forwarding destination
$dest = $client->incomingSources->createDestination($projectId, $sourceId, [
    'url' => 'https://your-api.com/webhooks/stripe',
    'enabled' => true,
    'maxAttempts' => 5,
    'timeoutSeconds' => 30,
]);

// List destinations
$dests = $client->incomingSources->listDestinations($projectId, $sourceId);

// Update destination
$client->incomingSources->updateDestination($projectId, $sourceId, $destId, [
    'enabled' => false,
]);

// Delete destination
$client->incomingSources->deleteDestination($projectId, $sourceId, $destId);
```

### Incoming Events

```php
// List incoming events (with optional source filter)
$events = $client->incomingEvents->list($projectId, [
    'sourceId' => $sourceId,
    'page' => 0,
    'size' => 20,
]);

// Get event details
$event = $client->incomingEvents->get($projectId, $eventId);

// Get forward attempts
$attempts = $client->incomingEvents->getAttempts($projectId, $eventId);

// Replay event to all destinations
$result = $client->incomingEvents->replay($projectId, $eventId);
echo "Replayed to {$result['destinationsCount']} destinations\n";
```

## Webhook Signature Verification

Verify incoming webhooks in your endpoint:

```php
<?php

use Railhook\Webhook;
use Railhook\Exception\RailhookException;

// Get raw request body
$payload = file_get_contents('php://input');
$headers = getallheaders();
$secret = getenv('WEBHOOK_SECRET');

try {
    // Option 1: Just verify
    Webhook::verifySignature($payload, $headers['X-Signature'] ?? '', $secret);

    // Option 2: Verify and parse
    $event = Webhook::constructEvent($payload, $headers, $secret);

    // $event['data'] is the decoded body; eventId / deliveryId / timestamp come
    // from the X-Event-Id / X-Delivery-Id / X-Timestamp headers. See "What
    // lands on your endpoint" below for $event['type'].
    echo "Delivery {$event['deliveryId']} of event {$event['eventId']}: "
        . json_encode($event['data']) . "\n";
    handleOrderCompleted($event['data']);

    http_response_code(200);
    echo 'OK';

} catch (RailhookException $e) {
    error_log("Webhook verification failed: {$e->getMessage()}");
    http_response_code(400);
    echo 'Invalid signature';
}
```

### What lands on your endpoint

Railhook PUTs the event's **payload** on the wire, not an envelope. This:

```php
$client->events->send('order.completed', ['orderId' => 'ord_1']);
```

arrives at your endpoint as the `data` array alone —

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

The signature is computed over `"{$timestamp}.{$rawBody}"` with HMAC-SHA256 and
the endpoint secret, and the server rejects timestamps more than **300
seconds** old — verify against the *raw* body from `php://input`, before any
`json_decode` and re-encode.

### Laravel Example

```php
<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use Railhook\Webhook;
use Railhook\Exception\RailhookException;

class WebhookController extends Controller
{
    public function handle(Request $request)
    {
        $payload = $request->getContent();
        $headers = $request->headers->all();
        
        try {
            $event = Webhook::constructEvent(
                $payload,
                $headers,
                config('services.webhook.secret')
            );

            // Process event...
            
            return response('OK', 200);

        } catch (RailhookException $e) {
            return response('Invalid signature', 400);
        }
    }
}
```

### Symfony Example

```php
<?php

namespace App\Controller;

use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Railhook\Webhook;
use Railhook\Exception\RailhookException;

class WebhookController
{
    public function handle(Request $request): Response
    {
        $payload = $request->getContent();
        $headers = $request->headers->all();

        try {
            $event = Webhook::constructEvent(
                $payload,
                $headers,
                $_ENV['WEBHOOK_SECRET']
            );

            // Process event...

            return new Response('OK', 200);

        } catch (RailhookException $e) {
            return new Response('Invalid signature', 400);
        }
    }
}
```

## Error Handling

```php
<?php

use Railhook\Exception\RailhookException;
use Railhook\Exception\RateLimitException;
use Railhook\Exception\AuthenticationException;
use Railhook\Exception\ValidationException;

try {
    $client->events->send('test', []);
} catch (RateLimitException $e) {
    // getRetryAfterMs() is milliseconds. getRateLimitInfo()['reset'] is the raw
    // X-RateLimit-Reset header, which the API sends in Unix *seconds*.
    $retryAfterMs = $e->getRetryAfterMs();
    echo "Rate limited. Retry after {$retryAfterMs}ms\n";
    usleep($retryAfterMs * 1000);
} catch (AuthenticationException $e) {
    echo "Invalid API key\n";
} catch (ValidationException $e) {
    echo "Validation failed: " . json_encode($e->getFieldErrors()) . "\n";
} catch (RailhookException $e) {
    echo "Error {$e->getStatusCode()}: {$e->getMessage()}\n";
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
| 400 | `validation_error` | `ValidationException` | Invalid request parameters; see `fieldErrors` |
| 400 | `invalid_request` | `RailhookException` | Malformed or semantically invalid request |
| 401 | `unauthorized` | `AuthenticationException` | Missing or invalid API key / expired token |
| 403 | `forbidden` | `RailhookException` | Insufficient permissions for the action |
| 404 | `not_found` | `NotFoundException` | Requested resource does not exist |
| 413 | `payload_too_large` | `RailhookException` | Request body exceeds maximum allowed size |
| 422 | `unprocessable_entity` | `RailhookException` | Valid syntax but violates business rules |
| 429 | `rate_limit_exceeded` | `RateLimitException` | Too many requests; check `X-RateLimit-*` headers |
| 500 | `internal_error` | `RailhookException` | Unexpected server error |

## Generic Requests

As the API expands, you can call any endpoint directly without waiting for SDK updates:

```php
// GET
$schemas = $client->get('/api/v1/projects/proj_123/schemas');

// GET with query params
$items = $client->get('/api/v1/projects/proj_123/items', ['status' => 'active']);

// POST with body and idempotency key
$result = $client->post('/api/v1/some/new/endpoint', ['key' => 'value'], 'unique-key');

// PUT
$client->put('/api/v1/projects/proj_123/settings', ['timezone' => 'UTC']);

// PATCH
$client->patch('/api/v1/projects/proj_123/settings', ['timezone' => 'UTC']);

// DELETE
$client->delete('/api/v1/projects/proj_123/tags/old-tag');

// Fully custom request (any HTTP method)
$data = $client->request('OPTIONS', '/api/v1/some/path');
```

All generic methods use the same authentication, error handling, and rate-limit logic as the built-in methods.

## Configuration

```php
$client = new Railhook(
    apiKey: getenv('RAILHOOK_API_KEY'), // Required: Your project API key
    baseUrl: 'https://api.example.com', // Optional (default: http://localhost:8080)
    timeout: 30                      // Optional: Request timeout in seconds (default: 30)
);
```

### Timeouts and retries

`timeout` is `CURLOPT_TIMEOUT`; hitting it throws `RailhookException` with
status `0` and a `cURL error: …` message, as does any connection-level failure.

**The client does not retry.** One SDK call is exactly one HTTP request — no
backoff, no idempotent replay. That is deliberate: `events->send()` accepts an
`$idempotencyKey`, so a retry policy belongs to the caller who knows whether
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

## Development

### Running Tests

**Local (requires PHP 8.1+):**
```bash
composer install
composer test
```

**Docker:**
```bash
docker run --rm -v $(pwd):/app -w /app composer:2 sh -c "composer install && composer test"
```

### Live-API smoke check

`composer test` stubs cURL, so it cannot see a renamed field. To drive the SDK
against a real instance:

```bash
make up                          # from the repo root
php scripts/live-api-smoke.php   # SMOKE_API_BASE_URL overrides the target

# or, with no local PHP:
docker run --rm --network host -v "$PWD":/app -w /app php:8.2-cli php scripts/live-api-smoke.php
```

It registers a throwaway org, walks endpoint → subscription → event →
deliveries → attempts → incoming, checks each error envelope, and verifies a
signature the running server itself produced. It is a script, not a test —
`phpunit.xml`'s only testsuite is `tests/`, so PHPUnit never collects it and
the unit suite still passes with no backend. It falls back to a PSR-4 shim when
`vendor/` is absent, so it runs without `composer install`.

## License

MIT
