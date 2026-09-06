# webhook-platform

Official Python SDK for [Railhook](https://github.com/vadymkykalo/railhook).

> The PyPI distribution is `webhook-platform`; the module you import is
> `railhook`. `pip install railhook`, then `from railhook import ...`.

**Scope.** This SDK covers Events, Endpoints, Subscriptions, Deliveries,
Incoming Sources, Incoming Events, and webhook signature verification —
7 of the platform's 35 API controllers. It does not wrap
Transformations, Rules, Workflows, Schemas, DLQ, Analytics, Usage, Alerts,
Incidents, PII rules, Audit Log, Tunnels, API keys, Members, or Projects —
use the [Generic Requests](#generic-requests) helpers for those until the
SDK grows to cover them.

## Installation

```bash
pip install railhook
```

## Quick Start

```python
import os

from railhook import Railhook, Event

client = Railhook(
    api_key=os.environ["RAILHOOK_API_KEY"],  # e.g. "Kz1uAIM8VeJUQN7yGSYCst64WxNLabBHfOYbrPlJ1yk"
    base_url="http://localhost:8080",  # optional
)

# Send an event
event = client.events.send(
    Event(
        type="order.completed",
        data={
            "order_id": "ord_12345",
            "amount": 99.99,
            "currency": "USD",
        },
    )
)

print(f"Event created: {event.event_id}")
print(f"Deliveries created: {event.deliveries_created}")
```

## API Reference

### Events

```python
from railhook import Event

# Send event with idempotency key
event = client.events.send(
    Event(type="order.completed", data={"order_id": "123"}),
    idempotency_key="unique-key",
)
```

### Endpoints

```python
from railhook import EndpointCreateParams, EndpointUpdateParams

# Create endpoint
endpoint = client.endpoints.create(
    project_id,
    EndpointCreateParams(
        url="https://api.example.com/webhooks",
        description="Production webhooks",
        enabled=True,
    ),
)

# List endpoints — the API paginates this one, so the endpoints are in .content
# (iterating the page yields them directly)
page = client.endpoints.list(project_id, page=0, size=20)
for endpoint in page:
    print(endpoint.url)

# Update endpoint
client.endpoints.update(
    project_id,
    endpoint_id,
    EndpointUpdateParams(enabled=False),
)

# Delete endpoint
client.endpoints.delete(project_id, endpoint_id)

# Rotate secret
updated = client.endpoints.rotate_secret(project_id, endpoint_id)
print(f"New secret: {updated.secret}")

# Test endpoint connectivity
result = client.endpoints.test(project_id, endpoint_id)
print(f"Test {'passed' if result.success else 'failed'}: {result.latency_ms}ms")
print(f"{result.http_status_code} — {result.message}")
```

### Subscriptions

```python
from railhook import SubscriptionCreateParams

# Subscribe endpoint to an event type
subscription = client.subscriptions.create(
    project_id,
    SubscriptionCreateParams(
        endpoint_id=endpoint.id,
        event_type="order.completed",
        enabled=True,
    ),
)

# List subscriptions — a bare list; unlike endpoints, this one is not paginated
subscriptions = client.subscriptions.list(project_id)

# Update subscription
client.subscriptions.update(
    project_id,
    subscription_id,
    event_type="order.shipped",
)

# Delete subscription
client.subscriptions.delete(project_id, subscription_id)
```

### Deliveries

```python
from railhook import DeliveryListParams, DeliveryStatus

# List deliveries with filters
deliveries = client.deliveries.list(
    project_id,
    DeliveryListParams(status=DeliveryStatus.FAILED, page=0, size=20),
)

print(f"Total failed: {deliveries.total_elements}")

# Get delivery attempts
attempts = client.deliveries.get_attempts(delivery_id)
for attempt in attempts:
    print(f"Attempt {attempt.attempt_number}: {attempt.http_status_code} ({attempt.duration_ms}ms)")

# Replay failed delivery
client.deliveries.replay(delivery_id)
```

## Incoming Webhooks

Receive, validate, and forward webhooks from third-party providers (Stripe, GitHub, Twilio, etc.).

### Incoming Sources

```python
from railhook import IncomingSourceCreateParams, IncomingSourceUpdateParams

# Create an incoming source with HMAC verification
source = client.incoming_sources.create(
    project_id,
    IncomingSourceCreateParams(
        name="Stripe Webhooks",
        slug="stripe",
        provider_type="STRIPE",
        verification_mode="HMAC_GENERIC",
        hmac_secret="whsec_...",
        hmac_header_name="Stripe-Signature",
    ),
)

print(f"Ingress URL: {source.ingress_url}")

# List sources
sources = client.incoming_sources.list(project_id)

# Update source
client.incoming_sources.update(
    project_id,
    source_id,
    IncomingSourceUpdateParams(name="Stripe Production", rate_limit_per_second=100),
)

# Delete source
client.incoming_sources.delete(project_id, source_id)
```

### Incoming Destinations

```python
from railhook import IncomingDestinationCreateParams

# Add a forwarding destination
dest = client.incoming_sources.create_destination(
    project_id,
    source_id,
    IncomingDestinationCreateParams(
        url="https://your-api.com/webhooks/stripe",
        enabled=True,
        max_attempts=5,
        timeout_seconds=30,
    ),
)

# List destinations
dests = client.incoming_sources.list_destinations(project_id, source_id)

# Delete destination
client.incoming_sources.delete_destination(project_id, source_id, dest_id)
```

### Incoming Events

```python
from railhook import IncomingEventListParams

# List incoming events (with optional source filter)
events = client.incoming_events.list(
    project_id,
    IncomingEventListParams(source_id=source.id, page=0, size=20),
)

# Get event details
event = client.incoming_events.get(project_id, event_id)

# Get forward attempts
attempts = client.incoming_events.get_attempts(project_id, event_id)

# Replay event to all destinations
result = client.incoming_events.replay(project_id, event_id)
print(f"Replayed to {result.destinations_count} destinations")
```

## Webhook Signature Verification

Verify incoming webhooks in your endpoint:

```python
from railhook import verify_signature, construct_event, RailhookError

# Flask example
from flask import Flask, request

app = Flask(__name__)

@app.route("/webhooks", methods=["POST"])
def handle_webhook():
    payload = request.get_data(as_text=True)
    headers = dict(request.headers)
    secret = os.environ["WEBHOOK_SECRET"]

    try:
        # Option 1: Just verify
        verify_signature(payload, headers.get("X-Signature", ""), secret)

        # Option 2: Verify and parse
        event = construct_event(payload, headers, secret)

        # event.data is the parsed body; event.event_id / event.delivery_id /
        # event.timestamp come from the X-Event-Id / X-Delivery-Id /
        # X-Timestamp headers. See "What lands on your endpoint" below for
        # event.type.
        print(f"Delivery {event.delivery_id} of event {event.event_id}: {event.data}")
        handle_order_completed(event.data)

        return "OK", 200

    except RailhookError as e:
        print(f"Webhook verification failed: {e.message}")
        return "Invalid signature", 400
```

### What lands on your endpoint

Railhook PUTs the event's **payload** on the wire, not an envelope. This:

```python
client.events.send(Event(type="order.completed", data={"order_id": "ord_1"}))
```

arrives at your endpoint as the ``data`` object alone —

```http
POST /webhooks HTTP/1.1
Content-Type: application/json
X-Signature: t=1738000000000,v1=<hex hmac-sha256>
X-Timestamp: 1738000000000
X-Event-Id: 6f0e…
X-Delivery-Id: 91ab…
X-Sequence-Number: 0
Idempotency-Key: 6f0e…-<endpoint-id>

{"order_id":"ord_1"}
```

So `construct_event` fills `event_id`, `delivery_id` and `timestamp` from the
headers and `data` from the body, but **`type` is empty**: the event type is
not on the wire for a default subscription. Route on the payload, on the
endpoint you registered, or set the subscription's `payload_template` to wrap
the event so `type` becomes part of the body.

The signature is computed over `f"{timestamp}.{raw_body}"` with HMAC-SHA256 and
the endpoint secret, and the server rejects timestamps more than **300
seconds** old — verify against the *raw* body bytes, before any JSON parse and
re-serialize.

### FastAPI Example

```python
from fastapi import FastAPI, Request, HTTPException
from railhook import construct_event, RailhookError

app = FastAPI()

@app.post("/webhooks")
async def handle_webhook(request: Request):
    payload = await request.body()
    headers = dict(request.headers)

    try:
        event = construct_event(
            payload.decode("utf-8"),
            headers,
            os.environ["WEBHOOK_SECRET"],
        )

        # Process event...
        return {"status": "ok"}

    except RailhookError as e:
        raise HTTPException(status_code=400, detail=e.message)
```

## Error Handling

```python
from railhook import (
    RailhookError,
    RateLimitError,
    AuthenticationError,
    ValidationError,
)

try:
    client.events.send(Event(type="test", data={}))
except RateLimitError as e:
    # retry_after_ms is milliseconds. e.rate_limit_info.reset is the raw
    # X-RateLimit-Reset header, which the API sends in Unix *seconds*.
    print(f"Rate limited. Retry after {e.retry_after_ms}ms")
    time.sleep(e.retry_after_ms / 1000)
except AuthenticationError:
    print("Invalid API key")
except ValidationError as e:
    print(f"Validation failed: {e.field_errors}")
except RailhookError as e:
    print(f"Error {e.status}: {e.message}")
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

```python
# GET
schemas = client.get("/api/v1/projects/proj_123/schemas")

# GET with query params
items = client.get("/api/v1/projects/proj_123/items", params={"status": "active"})

# POST with body and idempotency key
result = client.post("/api/v1/some/new/endpoint", body={"key": "value"}, idempotency_key="unique-key")

# PUT
client.put("/api/v1/projects/proj_123/settings", body={"timezone": "UTC"})

# PATCH
client.patch("/api/v1/projects/proj_123/settings", body={"timezone": "UTC"})

# DELETE
client.delete("/api/v1/projects/proj_123/tags/old-tag")
```

All generic methods use the same authentication, error handling, and rate-limit logic as the built-in methods.

## Configuration

```python
client = Railhook(
    api_key=os.environ["RAILHOOK_API_KEY"],  # Required: Your project API key
    base_url="https://api.example.com",  # Optional (default: http://localhost:8080)
    timeout=30,                     # Optional: Request timeout in seconds (default: 30)
)
```

### Timeouts and retries

`timeout` is passed straight to `requests`; hitting it raises `RailhookError`
with `code="timeout"` and `status=0`. A connection-level failure raises the
same class with `code="network_error"`.

**The client does not retry.** One SDK call is exactly one HTTP request — no
backoff, no idempotent replay, and no `urllib3` `Retry` adapter is installed.
That is deliberate: `events.send` accepts an `idempotency_key`, so a retry
policy belongs to the caller who knows whether reissuing the request is safe.
What *is* retried is the delivery itself, by the platform, on the
subscription's `retry_delays` ladder.

## Authentication

Every request the client makes carries `X-API-Key: <your key>` — the project
API key, created in the dashboard or via
`POST /api/v1/projects/{project_id}/api-keys`. The SDK never sends a bearer
token and has no login surface: JWT-authenticated endpoints (auth, projects,
organizations, members, API keys) are not part of it. Bootstrapping a project
and a key is a one-time step you do with the dashboard, the CLI, or plain
HTTP.

## Type Hints

This SDK includes full type hints for better IDE support:

```python
from railhook import (
    Event,
    EventResponse,
    Endpoint,
    Delivery,
    DeliveryStatus,
)
```

## Development

### Running Tests

**Local (requires Python 3.8+):**
```bash
pip install -e ".[dev]"
pytest
```

**Docker:**
```bash
docker run --rm -v $(pwd):/app -w /app python:3.11-slim sh -c "pip install -e '.[dev]' && pytest"
```

### Live-API smoke check

`pytest` stubs the transport, so it cannot see a renamed field. To drive the
SDK against a real instance:

```bash
make up                          # from the repo root
python scripts/live_api_smoke.py # SMOKE_API_BASE_URL overrides the target
```

It registers a throwaway org, walks endpoint → subscription → event →
deliveries → attempts → incoming, checks each error envelope, and verifies a
signature the running server itself produced. It is not collected by `pytest`
(`testpaths = tests`, `python_files = test_*.py`), so the unit suite still
passes with no backend.

## License

MIT
