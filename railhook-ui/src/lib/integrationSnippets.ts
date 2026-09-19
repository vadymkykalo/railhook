import type { SignatureScheme } from '../types/api.types';
import { CLI_LISTEN_EXAMPLE } from './publicSnippets';

/**
 * The code a developer copies out of the dashboard to integrate with it, one builder per task.
 *
 * Every SDK call here is one that exists in `sdks/node/src`, `sdks/python/railhook` and
 * `sdks/php/src` under that exact name and argument order; the docs pages carry the same samples.
 * Change an SDK signature and these go stale silently, which is why `integrationSnippets.test.ts`
 * pins the method names.
 *
 * Two rules hold for every builder: the API key is always the `RAILHOOK_API_KEY` environment
 * variable, never a key pasted into text that ends up in a repository, and the base URL is the
 * deployment the dashboard itself talks to, so a copied sample runs as-is against this instance.
 */

export type SnippetLanguage = 'curl' | 'node' | 'python' | 'php' | 'html' | 'cli';
export type SnippetSamples = Partial<Record<SnippetLanguage, string>>;

const SAMPLE_EVENT_TYPE = 'order.completed';

/** `{…}` as each language writes an object literal: the sample event's payload. */
const SAMPLE_DATA = {
  json: '{"orderId":"ord_12345","amount":99.99}',
  node: "{ orderId: 'ord_12345', amount: 99.99 }",
  python: '{"order_id": "ord_12345", "amount": 99.99}',
  php: "['orderId' => 'ord_12345', 'amount' => 99.99]",
};

function nodeClient(baseUrl: string, name = 'client'): string {
  return `// npm install @railhook/node
import { Railhook } from '@railhook/node';

const ${name} = new Railhook({
  apiKey: process.env.RAILHOOK_API_KEY,
  baseUrl: '${baseUrl}',
});`;
}

function pythonClient(baseUrl: string, imports: string): string {
  return `# pip install railhook
import os

from railhook import ${imports}

client = Railhook(
    api_key=os.environ["RAILHOOK_API_KEY"],
    base_url="${baseUrl}",
)`;
}

function phpClient(baseUrl: string): string {
  return `<?php
// composer require railhook/php
use Railhook\\Railhook;

$client = new Railhook(
    apiKey: getenv('RAILHOOK_API_KEY'),
    baseUrl: '${baseUrl}',
);`;
}

function curlPost(url: string, body: string): string {
  return [
    `curl -X POST ${url} \\`,
    '  -H "X-API-Key: $RAILHOOK_API_KEY" \\',
    '  -H "Content-Type: application/json" \\',
    `  -d '${body}'`,
  ].join('\n');
}

/** One event, sent the way a customer's backend sends every event after it. */
export function sendEventSnippets({ baseUrl, eventType = SAMPLE_EVENT_TYPE }: { baseUrl: string; eventType?: string }): SnippetSamples {
  return {
    curl: curlPost(`${baseUrl}/api/v1/events`, `{"type":"${eventType}","data":${SAMPLE_DATA.json}}`),
    node: `${nodeClient(baseUrl)}

const event = await client.events.send({
  type: '${eventType}',
  data: ${SAMPLE_DATA.node},
});
console.log(event.eventId, event.deliveriesCreated);`,
    python: `${pythonClient(baseUrl, 'Event, Railhook')}

event = client.events.send(Event(
    type="${eventType}",
    data=${SAMPLE_DATA.python},
))
print(event.event_id, event.deliveries_created)`,
    php: `${phpClient(baseUrl)}

$event = $client->events->send(
    type: '${eventType}',
    data: ${SAMPLE_DATA.php},
);
echo $event['eventId'];`,
  };
}

/**
 * What a customer's backend does to show one of its users the portal: create that user's
 * Consumer once, then open a session each time the page loads. With a `consumerId` the Consumer
 * already exists, so only the session is opened, for that one.
 */
export function consumerPortalSnippets({
  baseUrl, projectId, consumerId,
}: { baseUrl: string; projectId: string; consumerId?: string }): SnippetSamples {
  const origin = 'https://app.example.com';
  const consumers = `${baseUrl}/api/v1/projects/${projectId}/consumers`;

  if (consumerId) {
    return {
      curl: curlPost(`${consumers}/${consumerId}/portal-sessions`, `{"allowedOrigin":"${origin}"}`),
      node: `${nodeClient(baseUrl, 'railhook')}

const session = await railhook.portalSessions.create('${projectId}', '${consumerId}', {
  allowedOrigin: '${origin}', // the page that embeds the portal
});
// session.url is the iframe's src`,
      python: `${pythonClient(baseUrl, 'PortalSessionCreateParams, Railhook')}

session = client.portal_sessions.create(
    "${projectId}",
    "${consumerId}",
    PortalSessionCreateParams(allowed_origin="${origin}"),
)
# session.url is the iframe's src`,
      php: `${phpClient(baseUrl)}

$session = $client->portalSessions->create('${projectId}', '${consumerId}', [
    'allowedOrigin' => '${origin}', // the page that embeds the portal
]);
// $session['url'] is the iframe's src`,
    };
  }

  return {
    curl: `# 1. Once per user: create their Consumer, keyed by your own id for them
${curlPost(consumers, '{"externalId":"user_123","name":"Acme Ltd"}')}

# 2. Each time they open your webhooks page: open a session with the "id" step 1 returned
${curlPost(`${consumers}/$CONSUMER_ID/portal-sessions`, `{"allowedOrigin":"${origin}"}`)}`,
    node: `${nodeClient(baseUrl, 'railhook')}

const PROJECT_ID = '${projectId}';

// Once per user: create their Consumer, keyed by your own id for them.
const consumer = await railhook.consumers.create(PROJECT_ID, {
  externalId: 'user_123',
  name: 'Acme Ltd',
});

// Each time they open your webhooks page: a short-lived session for them alone.
const session = await railhook.portalSessions.create(PROJECT_ID, consumer.id, {
  allowedOrigin: '${origin}', // the page that embeds the portal
});
// session.url is the iframe's src`,
    python: `${pythonClient(baseUrl, 'ConsumerCreateParams, PortalSessionCreateParams, Railhook')}

PROJECT_ID = "${projectId}"

# Once per user: create their Consumer, keyed by your own id for them.
consumer = client.consumers.create(
    PROJECT_ID,
    ConsumerCreateParams(external_id="user_123", name="Acme Ltd"),
)

# Each time they open your webhooks page: a short-lived session for them alone.
session = client.portal_sessions.create(
    PROJECT_ID,
    consumer.id,
    PortalSessionCreateParams(allowed_origin="${origin}"),
)
# session.url is the iframe's src`,
    php: `${phpClient(baseUrl)}

$projectId = '${projectId}';

// Once per user: create their Consumer, keyed by your own id for them.
$consumer = $client->consumers->create($projectId, [
    'externalId' => 'user_123',
    'name' => 'Acme Ltd',
]);

// Each time they open your webhooks page: a short-lived session for them alone.
$session = $client->portalSessions->create($projectId, $consumer['id'], [
    'allowedOrigin' => '${origin}', // the page that embeds the portal
]);
// $session['url'] is the iframe's src`,
  };
}

/** The page side of the portal: the session's URL in a frame. */
export function portalIframeSnippet(url = '{{ session.url }}'): SnippetSamples {
  return {
    html: `<iframe
  src="${url}"
  style="width: 100%; height: 720px; border: 0"
  title="Webhooks"
></iframe>`,
  };
}

function pathOf(endpointUrl: string | undefined): string {
  if (!endpointUrl) return '/webhooks';
  try {
    const path = new URL(endpointUrl).pathname;
    return path && path !== '/' ? path : '/webhooks';
  } catch {
    return '/webhooks';
  }
}

/**
 * The receiving side: a handler on the endpoint's own path that rejects anything unsigned.
 *
 * `X-Signature` unless the endpoint sends only the Standard Webhooks headers — a `BOTH` endpoint
 * carries both and either check is enough. The SDKs take the raw secret for either scheme: the
 * `whsec_` form is that same secret, base64-encoded.
 */
export function verifySignatureSnippets({
  scheme, endpointUrl,
}: { scheme?: SignatureScheme; endpointUrl?: string }): SnippetSamples {
  const path = pathOf(endpointUrl);

  if (scheme === 'STANDARD') {
    return {
      node: `import express from 'express';
import { verifyStandardWebhook } from '@railhook/node';

const app = express();

// express.raw, not express.json: the signature covers the exact bytes that arrived.
app.post('${path}', express.raw({ type: 'application/json' }), (req, res) => {
  const body = req.body.toString('utf8');
  try {
    verifyStandardWebhook(body, req.headers, process.env.WEBHOOK_SECRET);
  } catch {
    return res.sendStatus(400);
  }
  const data = JSON.parse(body);
  // Deduplicate on the webhook-id header: a retry carries the same one.
  res.sendStatus(200);
});`,
      python: `import os

from flask import Flask, request
from railhook import RailhookError, verify_standard_webhook

app = Flask(__name__)

@app.route("${path}", methods=["POST"])
def handle_webhook():
    body = request.get_data(as_text=True)
    try:
        verify_standard_webhook(body, dict(request.headers), os.environ["WEBHOOK_SECRET"])
    except RailhookError:
        return "Invalid signature", 400
    # Deduplicate on the webhook-id header: a retry carries the same one.
    return "OK", 200`,
      php: `<?php
use Railhook\\Webhook;
use Railhook\\Exception\\RailhookException;

$payload = file_get_contents('php://input');

try {
    Webhook::verifyStandardWebhook($payload, getallheaders(), getenv('WEBHOOK_SECRET'));
    $data = json_decode($payload, true);
    http_response_code(200);
} catch (RailhookException $e) {
    http_response_code(400);
}`,
    };
  }

  return {
    node: `import express from 'express';
import { constructEvent } from '@railhook/node';

const app = express();

// express.raw, not express.json: the signature covers the exact bytes that arrived.
app.post('${path}', express.raw({ type: 'application/json' }), (req, res) => {
  try {
    const event = constructEvent(req.body.toString('utf8'), req.headers, process.env.WEBHOOK_SECRET);
    // event.data is the payload; event.deliveryId is safe to deduplicate on.
    res.sendStatus(200);
  } catch {
    res.sendStatus(400);
  }
});`,
    python: `import os

from flask import Flask, request
from railhook import RailhookError, construct_event

app = Flask(__name__)

@app.route("${path}", methods=["POST"])
def handle_webhook():
    payload = request.get_data(as_text=True)
    try:
        event = construct_event(payload, dict(request.headers), os.environ["WEBHOOK_SECRET"])
    except RailhookError:
        return "Invalid signature", 400
    # event.data is the payload; event.delivery_id is safe to deduplicate on.
    return "OK", 200`,
    php: `<?php
use Railhook\\Webhook;
use Railhook\\Exception\\RailhookException;

$payload = file_get_contents('php://input');

try {
    $event = Webhook::constructEvent($payload, getallheaders(), getenv('WEBHOOK_SECRET'));
    // $event['data'] is the payload; $event['deliveryId'] is safe to deduplicate on.
    http_response_code(200);
} catch (RailhookException $e) {
    http_response_code(400);
}`,
  };
}

/**
 * A test webhook at an incoming source. Only curl: the real sender is the provider, and what a
 * developer does by hand is check that the URL answers.
 */
export function ingressCurlSnippet(ingressUrl: string, signatureHeader?: string, signaturePrefix?: string | null): SnippetSamples {
  const lines = [`curl -X POST ${ingressUrl} \\`, '  -H "Content-Type: application/json" \\'];
  if (signatureHeader) lines.push(`  -H "${signatureHeader}: ${signaturePrefix ?? ''}<hmac-sha256-hex-of-body>" \\`);
  lines.push(`  -d '{"event": "test", "data": {}}'`);
  return { curl: lines.join('\n') };
}

/**
 * Install the CLI from this deployment, sign it in here, and open a tunnel to a local port. The
 * installer is served by the site (nginx), the login goes to the API; one origin in production,
 * two in some local setups.
 */
export function cliTunnelSnippets({ siteUrl, apiUrl }: { siteUrl: string; apiUrl: string }): SnippetSamples {
  return {
    cli: `curl -fsSL ${siteUrl}/install-cli.sh | bash
railhook login --server ${apiUrl}
${CLI_LISTEN_EXAMPLE}`,
  };
}
