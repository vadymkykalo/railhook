/**
 * The code samples the guides show.
 *
 * Only samples that teach something live here: a first request, a signature
 * verification, a CLI session. Per-endpoint request/response examples used to
 * live in this file too — they now come from the spec, in `ApiReference`.
 */

const BASE = 'https://your-api.com';

export const authSamples = {
  login: {
    curl: `curl -X POST ${BASE}/api/v1/auth/login \\
  -H "Content-Type: application/json" \\
  -d '{"email":"you@company.com","password":"••••••••"}'`,
    node: `const res = await fetch('${BASE}/api/v1/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: 'you@company.com', password: process.env.PASSWORD }),
});
const { accessToken } = await res.json();`,
    python: `import os

import requests

res = requests.post(
    "${BASE}/api/v1/auth/login",
    json={"email": "you@company.com", "password": os.environ["PASSWORD"]},
)
access_token = res.json()["accessToken"]`,
  },
  bearer: `Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...`,
  apiKey: `X-API-Key: Kz1uAIM8VeJUQN7yGSYCst64WxNLabBHfOYbrPlJ1yk`,
};

export const quickstartSamples = {
  project: `curl -X POST ${BASE}/api/v1/projects \\
  -H "Authorization: Bearer $ACCESS_TOKEN" \\
  -H "Content-Type: application/json" \\
  -d '{"name":"Production"}'`,
  apiKey: `curl -X POST ${BASE}/api/v1/projects/$PROJECT_ID/api-keys \\
  -H "Authorization: Bearer $ACCESS_TOKEN" \\
  -H "Content-Type: application/json" \\
  -d '{"name":"Production key"}'`,
  endpoint: `curl -X POST ${BASE}/api/v1/projects/$PROJECT_ID/endpoints \\
  -H "X-API-Key: $API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"url":"https://api.customer.com/webhooks","description":"Orders"}'`,
  subscription: `curl -X POST ${BASE}/api/v1/projects/$PROJECT_ID/subscriptions \\
  -H "X-API-Key: $API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"endpointId":"$ENDPOINT_ID","eventType":"order.completed"}'`,
  event: `curl -X POST ${BASE}/api/v1/events \\
  -H "X-API-Key: $API_KEY" \\
  -H "Content-Type: application/json" \\
  -H "Idempotency-Key: order-12345-completed" \\
  -d '{"type":"order.completed","data":{"orderId":"ord_12345","amount":99.99}}'`,
};

export const signatureSamples = {
  curl: `# Hookflow signs "<timestamp>.<raw body>" with the endpoint secret.
# X-Signature: t=<unix-ms>,v1=<hex hmac-sha256>

SIGNED="\${TIMESTAMP}.\${BODY}"
EXPECTED=$(printf '%s' "$SIGNED" | openssl dgst -sha256 -hmac "$SECRET" | cut -d' ' -f2)
[ "$EXPECTED" = "$V1" ] || exit 1`,
  node: `import crypto from 'node:crypto';

export function verify(rawBody, header, secret) {
  const parts = Object.fromEntries(header.split(',').map((p) => p.split('=')));
  const expected = crypto
    .createHmac('sha256', secret)
    .update(\`\${parts.t}.\${rawBody}\`)
    .digest('hex');

  // Constant time — a fast reject leaks the signature one byte at a time.
  // timingSafeEqual throws on a length mismatch, so check the length first.
  const provided = Buffer.from(parts.v1 ?? '', 'utf8');
  const digest = Buffer.from(expected, 'utf8');
  const ok = provided.length === digest.length && crypto.timingSafeEqual(digest, provided);
  // Reject anything older than five minutes so a captured request cannot be replayed.
  const fresh = Math.abs(Date.now() - Number(parts.t)) < 5 * 60 * 1000;
  return ok && fresh;
}`,
  python: `import hashlib, hmac, time

def verify(raw_body: bytes, header: str, secret: str) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(","))
    expected = hmac.new(
        secret.encode(), f"{parts['t']}.".encode() + raw_body, hashlib.sha256
    ).hexdigest()
    fresh = abs(time.time() * 1000 - int(parts["t"])) < 5 * 60 * 1000
    return hmac.compare_digest(expected, parts["v1"]) and fresh`,
};

/**
 * Verifying the Standard Webhooks headers.
 *
 * These lean on the SDKs rather than reimplementing the HMAC, because that is
 * the whole point of speaking the convention: the receiver adds a dependency
 * instead of reading a signing specification. The shell sample is the exception
 * — there is no SDK there, and it is the only place the three differences from
 * `X-Signature` are visible at once: the id is part of what is signed, the
 * timestamp is seconds, and the digest is base64.
 */
export const standardSignatureSamples = {
  curl: `# signed: "<webhook-id>.<webhook-timestamp>.<raw body>"
# the key is the endpoint's standardWebhooksSecret, whsec_<base64>, decoded

KEY_HEX=$(printf '%s' "\${WHSEC#whsec_}" | base64 -d | xxd -p | tr -d '\\n')
SIGNED="\${WEBHOOK_ID}.\${WEBHOOK_TIMESTAMP}.\${BODY}"
EXPECTED=$(printf '%s' "$SIGNED" \\
  | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$KEY_HEX" -binary | base64)

# webhook-signature is "v1,<sig>", space-separated one per valid secret during
# a rotation window — any one of them matching is enough.
case " $WEBHOOK_SIGNATURE " in *" v1,$EXPECTED "*) ;; *) exit 1 ;; esac`,
  node: `import express from 'express';
import { verifyStandardWebhook } from '@webhook-platform/node';

// express.raw, not express.json: the signature is over the bytes that arrived,
// and a parsed-then-reserialized body hashes differently.
app.post('/webhooks', express.raw({ type: 'application/json' }), (req, res) => {
  try {
    // The endpoint's standardWebhooksSecret — the whsec_… form, not the raw one.
    verifyStandardWebhook(req.body.toString('utf8'), req.headers, process.env.HOOKFLOW_WHSEC);
  } catch {
    return res.sendStatus(400); // wrong signature, or older than 300 seconds
  }

  const event = JSON.parse(req.body.toString('utf8'));
  return res.sendStatus(204);
});`,
  python: `import os

from fastapi import HTTPException, Request, Response
from hookflow import HookflowError, verify_standard_webhook


@app.post("/webhooks")
async def receive(request: Request) -> Response:
    # The raw body, not the parsed one: the signature is over the bytes that
    # arrived, and a reserialized body hashes differently.
    body = (await request.body()).decode()
    try:
        # The endpoint's standardWebhooksSecret — the whsec_… form.
        verify_standard_webhook(body, dict(request.headers), os.environ["HOOKFLOW_WHSEC"])
    except HookflowError:
        raise HTTPException(status_code=400)  # wrong signature, or too old

    return Response(status_code=204)`,
};

export const challengeSamples = {
  request: `POST https://your-endpoint.com/webhooks
Content-Type: application/json

{
  "type": "webhook.verification",
  "challenge": "whc_abc123xyz789",
  "timestamp": "2024-01-15T10:30:00Z"
}`,
  response: `HTTP/1.1 200 OK
Content-Type: application/json

{ "challenge": "whc_abc123xyz789" }`,
};

export const replaySamples = {
  header: `# the event was sent with an Idempotency-Key:
Idempotency-Key: <event-idempotency-key>-<endpoint-id>

# under AUTO, where the platform generated one for it (a random UUID):
Idempotency-Key: <generated-uuid>-<endpoint-id>

# the delivery carries no key of its own — no key was sent under NONE,
# or the delivery was built by a time-range replay:
Idempotency-Key: <event-id>-<endpoint-id>`,
  dryRun: `{
  "deliveryId": "…",
  "endpointUrl": "https://your-app.com/webhook",
  "eventType": "order.created",
  "idempotencyKey": "idem-key-123-endpoint-456",
  "plan": "WILL_SEND: POST https://your-app.com/webhook (attempt 4/7)",
  "previousAttemptCount": 3,
  "maxAttempts": 7,
  "currentStatus": "FAILED"
}`,
};

export const rulesSample = `curl -X POST ${BASE}/api/v1/projects/$PROJECT_ID/rules \\
  -H "X-API-Key: $API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "name": "Route high-value orders to fraud detection",
    "enabled": true,
    "priority": 10,
    "eventTypePattern": "order.*",
    "conditions": {
      "type": "group",
      "op": "AND",
      "children": [
        { "type": "predicate", "field": "data.amount", "operator": "GTE", "value": 1000, "valueType": "NUMBER" },
        { "type": "predicate", "field": "data.currency", "operator": "IN", "value": ["USD","EUR"], "valueType": "ARRAY_STRING" }
      ]
    },
    "actions": [
      { "type": "ROUTE", "endpointId": "$FRAUD_ENDPOINT_ID", "sortOrder": 0 },
      { "type": "TAG", "config": { "tag": "high-value" }, "sortOrder": 1 }
    ]
  }'`;

export const schemaSample = `curl -X POST ${BASE}/api/v1/projects/$PROJECT_ID/schemas/$EVENT_TYPE_ID/versions \\
  -H "X-API-Key: $API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "schemaJson": "{\\"type\\":\\"object\\",\\"required\\":[\\"order_id\\"],\\"properties\\":{\\"order_id\\":{\\"type\\":\\"string\\"}}}",
    "compatibilityMode": "BACKWARD"
  }'`;

export const errorSamples = {
  envelope: `{
  "error": "validation_error",
  "message": "Invalid request parameters",
  "status": 400,
  "fieldErrors": {
    "type": "Event type is required",
    "data": "Event data cannot be empty"
  }
}`,
};

export const cliSamples = {
  install: `curl -fsSL https://raw.githubusercontent.com/vadymkykalo/webhook-platform/main/webhook-platform-cli/install.sh | bash`,
  docker: `docker run --rm -it -v ~/.config/hookflow:/root/.config/hookflow \\
  ghcr.io/vadymkykalo/hookflow-cli:latest listen 3000`,
  login: `hookflow login

# ▸ Open: http://localhost/device?code=ABCD-1234
# ▸ Code: ABCD-1234
# ✓ Logged in as you@company.com`,
  listen: `hookflow listen 3000

#   Public URL:  https://tun-x4k9.example.com/t/tun-x4k9
#   Forwarding:  → http://localhost:3000
#   Press Ctrl+C to stop`,
  profiles: `hookflow config profile create staging --url https://staging.company.com
hookflow config profile use staging && hookflow login
hookflow config profile list`,
};

export const sdkSamples = {
  node: `import { Hookflow } from '@webhook-platform/node';

const client = new Hookflow({ apiKey: process.env.HOOKFLOW_API_KEY });

const event = await client.events.send({
  type: 'order.completed',
  data: { orderId: 'ord_12345', amount: 99.99 },
});`,
  python: `import os

from hookflow import Hookflow, Event

client = Hookflow(api_key=os.environ["HOOKFLOW_API_KEY"])

event = client.events.send(
    Event(type="order.completed", data={"order_id": "ord_12345", "amount": 99.99})
)`,
  php: `<?php
use Hookflow\\Hookflow;

$client = new Hookflow(apiKey: getenv('HOOKFLOW_API_KEY'));

$event = $client->events->send(
    type: 'order.completed',
    data: ['orderId' => 'ord_12345', 'amount' => 99.99],
);`,
};

/**
 * What a delivery looks like while a rotated secret is inside its grace window.
 *
 * The two `v1` values are the same body signed with the new secret and with the one being
 * retired. A receiver that has deployed either of them verifies; the point of showing the
 * header rather than describing it is that a verifier written to read "the" v1 will silently
 * pick one and reject half the traffic.
 */
export const rotationSample = `POST /webhooks/orders HTTP/1.1
Host: api.customer.com
X-Signature: t=1735689600000,v1=8f2a41c7...new,v1=3b90de55...retired
X-Event-Id: evt_9c41e8f2
X-Timestamp: 1735689600000
Content-Type: application/json`;

