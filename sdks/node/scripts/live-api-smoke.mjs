#!/usr/bin/env node
// Against a running API (`make up`, then `npm run smoke:live`); kept out of `npm test`.
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const distEntry = path.join(here, '..', 'dist', 'index.js');

if (!fs.existsSync(distEntry)) {
  console.error(`Built SDK not found at ${distEntry}. Run \`npm run build\` first.`);
  process.exit(2);
}

const { Railhook, verifySignature, generateSignature, AuthenticationError, NotFoundError, ValidationError, RailhookError } =
  require(distEntry);

const BASE_URL = process.env.SMOKE_API_BASE_URL || 'http://localhost:8080';
const PASSWORD = 'SmokeCheck!2026x'; // meets AuthController's complexity policy

let passed = 0;
const failures = [];

function check(label, fn) {
  try {
    fn();
    passed += 1;
    console.log(`  ok   ${label}`);
  } catch (err) {
    failures.push(`${label}: ${err.message}`);
    console.log(`  FAIL ${label}\n         ${err.message}`);
  }
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

function eq(actual, expected, what) {
  assert(
    actual === expected,
    `${what}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`
  );
}

// Only for bootstrapping a tenant: the SDK is API-key scoped and cannot register or create projects.
async function raw(method, path, body, headers = {}) {
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  });
  const text = await res.text();
  const parsed = text ? JSON.parse(text) : undefined;
  if (!res.ok) throw new Error(`${method} ${path} -> HTTP ${res.status} ${text}`);
  return parsed;
}

async function apiIsUp() {
  try {
    // Any response to an invalid login proves the API is up; /v3/api-docs is off by default.
    const res = await fetch(`${BASE_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
      signal: AbortSignal.timeout(5000),
    });
    return res.status > 0;
  } catch {
    return false;
  }
}

async function main() {
  console.log(`Railhook Node SDK — live API smoke check against ${BASE_URL}\n`);

  if (!(await apiIsUp())) {
    console.error(`${BASE_URL} is not answering. Start the stack with \`make up\` from the repo root.`);
    process.exit(2);
  }

  const suffix = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const auth = await raw('POST', '/api/v1/auth/register', {
    email: `node-smoke-${suffix}@node-smoke.invalid`,
    password: PASSWORD,
    fullName: 'Node Smoke Check',
    organizationName: `node-smoke-${suffix}`.slice(0, 100),
  });

  console.log('register:');
  check('register returns an accessToken', () => assert(typeof auth.accessToken === 'string' && auth.accessToken.length > 0, 'no accessToken'));
  check('register returns refreshToken: null (nothing may assume it is present)', () =>
    eq(auth.refreshToken, null, 'refreshToken'));
  check('register reports emailVerified', () => eq(typeof auth.emailVerified, 'boolean', 'typeof emailVerified'));

  const bearer = { Authorization: `Bearer ${auth.accessToken}` };
  const project = await raw('POST', '/api/v1/projects', { name: `node-smoke-${suffix}`.slice(0, 100) }, bearer);
  // name has a 2-char minimum (ApiKeyRequest); a one-letter name is a 400.
  const apiKey = await raw(
    'POST',
    `/api/v1/projects/${project.id}/api-keys`,
    { name: `node-smoke-key-${suffix}`.slice(0, 100), scope: 'READ_WRITE' },
    bearer
  );

  const projectId = project.id;
  const client = new Railhook({ apiKey: apiKey.key, baseUrl: BASE_URL });

  console.log('\nendpoints:');
  const endpoint = await client.endpoints.create(projectId, {
    url: 'https://example.com/node-smoke',
    description: 'node live smoke check',
    enabled: true,
  });
  check('endpoints.create returns the declared Endpoint shape', () => {
    eq(typeof endpoint.id, 'string', 'typeof id');
    eq(endpoint.projectId, projectId, 'projectId');
    eq(endpoint.url, 'https://example.com/node-smoke', 'url');
    eq(endpoint.enabled, true, 'enabled');
    eq(typeof endpoint.createdAt, 'string', 'typeof createdAt');
    eq(typeof endpoint.secret, 'string', 'typeof secret');
  });

  const endpointPage = await client.endpoints.list(projectId);
  check('endpoints.list returns a page envelope, not a bare array', () => {
    assert(!Array.isArray(endpointPage), 'endpoints.list returned an array; the API sends a page envelope');
    assert(Array.isArray(endpointPage.content), 'page.content is not an array');
    eq(typeof endpointPage.totalElements, 'number', 'typeof totalElements');
    assert(endpointPage.content.some((e) => e.id === endpoint.id), 'created endpoint missing from page.content');
  });

  const fetched = await client.endpoints.get(projectId, endpoint.id);
  check('endpoints.get round-trips the endpoint', () => eq(fetched.id, endpoint.id, 'id'));

  const testResult = await client.endpoints.test(projectId, endpoint.id);
  check('endpoints.test returns httpStatusCode/latencyMs (not httpStatus)', () => {
    eq(typeof testResult.success, 'boolean', 'typeof success');
    eq(typeof testResult.latencyMs, 'number', 'typeof latencyMs');
    assert('httpStatusCode' in testResult, 'no httpStatusCode field in the test result');
  });

  const rotated = await client.endpoints.rotateSecret(projectId, endpoint.id);
  check('endpoints.rotateSecret returns a different secret', () => {
    eq(typeof rotated.secret, 'string', 'typeof secret');
    assert(rotated.secret !== endpoint.secret, 'secret did not change');
  });

  console.log('\nsubscriptions:');
  const subscription = await client.subscriptions.create(projectId, {
    endpointId: endpoint.id,
    eventType: 'order.completed',
  });
  check('subscriptions.create returns the declared Subscription shape', () => {
    eq(subscription.endpointId, endpoint.id, 'endpointId');
    eq(subscription.eventType, 'order.completed', 'eventType');
    eq(typeof subscription.maxAttempts, 'number', 'typeof maxAttempts');
    eq(typeof subscription.timeoutSeconds, 'number', 'typeof timeoutSeconds');
  });
  check('Subscription declares the transformationId/transformationName the API sends', () => {
    assert('transformationId' in subscription, 'transformationId missing from the response');
    assert('transformationName' in subscription, 'transformationName missing from the response');
  });

  const subs = await client.subscriptions.list(projectId);
  check('subscriptions.list returns a bare array (it is NOT paginated)', () => {
    assert(Array.isArray(subs), 'subscriptions.list did not return an array');
    assert(subs.some((s) => s.id === subscription.id), 'created subscription missing');
  });

  console.log('\nevents:');
  const event = await client.events.send(
    { type: 'order.completed', data: { orderId: 'ord_12345', amount: 99.99 } },
    `node-smoke-${suffix}`
  );
  check('events.send returns { eventId, type, createdAt, deliveriesCreated }', () => {
    eq(typeof event.eventId, 'string', 'typeof eventId');
    eq(event.type, 'order.completed', 'type');
    eq(typeof event.createdAt, 'string', 'typeof createdAt');
    eq(event.deliveriesCreated, 1, 'deliveriesCreated');
  });

  console.log('\ndeliveries:');
  let page = { content: [] };
  for (let i = 0; i < 20 && page.content.length === 0; i++) {
    page = await client.deliveries.list(projectId, { size: 5 });
    if (page.content.length === 0) await new Promise((r) => setTimeout(r, 500));
  }
  check('deliveries.list returns the paginated envelope with the delivery in it', () => {
    assert(Array.isArray(page.content), 'page.content is not an array');
    eq(typeof page.totalElements, 'number', 'typeof totalElements');
    eq(typeof page.number, 'number', 'typeof number');
    eq(typeof page.size, 'number', 'typeof size');
    assert(page.content.length > 0, 'no delivery was created for the event');
  });

  const delivery = page.content[0];
  check('Delivery carries the declared field names (nextRetryAt, not nextAttemptAt)', () => {
    eq(delivery.eventId, event.eventId, 'eventId');
    eq(delivery.endpointId, endpoint.id, 'endpointId');
    eq(delivery.subscriptionId, subscription.id, 'subscriptionId');
    eq(typeof delivery.attemptCount, 'number', 'typeof attemptCount');
    assert('nextRetryAt' in delivery, 'nextRetryAt missing from the delivery');
  });

  const one = await client.deliveries.get(delivery.id);
  check('deliveries.get round-trips the delivery', () => eq(one.id, delivery.id, 'id'));

  let attempts = [];
  for (let i = 0; i < 20 && attempts.length === 0; i++) {
    attempts = await client.deliveries.getAttempts(delivery.id);
    if (attempts.length === 0) await new Promise((r) => setTimeout(r, 500));
  }
  check('deliveries.getAttempts returns httpStatusCode/durationMs/createdAt', () => {
    assert(Array.isArray(attempts), 'getAttempts did not return an array');
    assert(attempts.length > 0, 'no attempt was recorded');
    const a = attempts[0];
    eq(typeof a.id, 'string', 'typeof id');
    eq(a.deliveryId, delivery.id, 'deliveryId');
    eq(typeof a.attemptNumber, 'number', 'typeof attemptNumber');
    assert('httpStatusCode' in a, 'httpStatusCode missing (the SDK used to call it httpStatus)');
    assert('durationMs' in a, 'durationMs missing (the SDK used to call it latencyMs)');
    assert('createdAt' in a, 'createdAt missing (the SDK used to call it attemptedAt)');
  });

  console.log('\nincoming:');
  const source = await client.incomingSources.create(projectId, {
    name: 'Node Smoke Source',
    slug: `node-smoke-${suffix}`.slice(0, 60),
    providerType: 'GENERIC',
    verificationMode: 'NONE',
  });
  check('incomingSources.create returns an ingress URL and token', () => {
    eq(typeof source.ingressUrl, 'string', 'typeof ingressUrl');
    eq(typeof source.ingressPathToken, 'string', 'typeof ingressPathToken');
    eq(source.status, 'ACTIVE', 'status');
  });

  const sourcePage = await client.incomingSources.list(projectId);
  check('incomingSources.list returns a page envelope', () => {
    assert(Array.isArray(sourcePage.content), 'page.content is not an array');
    assert(sourcePage.content.some((s) => s.id === source.id), 'created source missing');
  });

  const destination = await client.incomingSources.createDestination(projectId, source.id, {
    url: 'https://example.com/node-smoke-destination',
    enabled: true,
  });
  check('createDestination returns the declared IncomingDestination shape', () => {
    eq(destination.incomingSourceId, source.id, 'incomingSourceId');
    eq(typeof destination.maxAttempts, 'number', 'typeof maxAttempts');
    eq(destination.authType, 'NONE', 'authType');
  });

  const destPage = await client.incomingSources.listDestinations(projectId, source.id);
  check('listDestinations returns a page envelope', () =>
    assert(destPage.content.some((d) => d.id === destination.id), 'created destination missing'));

  // The ingress URL is the only way to make an Incoming Event exist.
  await raw('POST', `/ingress/${source.ingressPathToken}`, { hello: 'incoming' });

  let incoming = { content: [] };
  for (let i = 0; i < 20 && incoming.content.length === 0; i++) {
    incoming = await client.incomingEvents.list(projectId, { sourceId: source.id, size: 5 });
    if (incoming.content.length === 0) await new Promise((r) => setTimeout(r, 500));
  }
  check('incomingEvents.list returns the received webhook', () => {
    assert(incoming.content.length > 0, 'the ingress POST produced no incoming event');
    const e = incoming.content[0];
    eq(e.incomingSourceId, source.id, 'incomingSourceId');
    eq(e.method, 'POST', 'method');
    eq(typeof e.receivedAt, 'string', 'typeof receivedAt');
  });

  if (incoming.content.length > 0) {
    const incomingId = incoming.content[0].id;
    const got = await client.incomingEvents.get(projectId, incomingId);
    check('incomingEvents.get round-trips the event', () => eq(got.id, incomingId, 'id'));

    const fwd = await client.incomingEvents.getAttempts(projectId, incomingId);
    check('incomingEvents.getAttempts returns a page envelope', () =>
      assert(Array.isArray(fwd.content), 'forward attempts are not in a page envelope'));

    const replayed = await client.incomingEvents.replay(projectId, incomingId);
    check('incomingEvents.replay returns { status, eventId, destinationsCount }', () => {
      eq(replayed.eventId, incomingId, 'eventId');
      eq(typeof replayed.destinationsCount, 'number', 'typeof destinationsCount');
      eq(typeof replayed.status, 'string', 'typeof status');
    });
  }

  console.log('\nerrors:');
  const badClient = new Railhook({ apiKey: 'not-a-real-key', baseUrl: BASE_URL });
  await expectRejection(
    'an invalid API key raises AuthenticationError(401)',
    () => badClient.events.send({ type: 'order.completed', data: {} }),
    (err) => {
      assert(err instanceof AuthenticationError, `expected AuthenticationError, got ${err.name}`);
      eq(err.status, 401, 'status');
    }
  );
  await expectRejection(
    'an unknown delivery raises NotFoundError(404)',
    () => client.deliveries.get('00000000-0000-0000-0000-000000000000'),
    (err) => {
      assert(err instanceof NotFoundError, `expected NotFoundError, got ${err.name}`);
      eq(err.status, 404, 'status');
    }
  );
  await expectRejection(
    'a malformed event type raises ValidationError(400) carrying fieldErrors',
    () => client.events.send({ type: 'NOT A VALID TYPE', data: {} }),
    (err) => {
      assert(err instanceof ValidationError, `expected ValidationError, got ${err.name}`);
      eq(err.status, 400, 'status');
      assert(err.fieldErrors && err.fieldErrors.type, 'fieldErrors.type was not parsed out of the envelope');
    }
  );
  await expectRejection(
    "another project's resources raise a 403 RailhookError",
    () => client.endpoints.list('00000000-0000-0000-0000-000000000000'),
    (err) => {
      assert(err instanceof RailhookError, `expected RailhookError, got ${err.name}`);
      eq(err.status, 403, 'status');
      eq(err.code, 'forbidden', 'code (taken from the envelope\'s "error" field)');
    }
  );

  console.log('\nsignature:');
  const dryRun = await client.post(`/api/v1/projects/${projectId}/transform-preview/delivery-dry-run`, {
    payload: JSON.stringify({ orderId: 'ord_12345' }),
    endpointId: endpoint.id,
    eventType: 'order.completed',
  });
  check('the server produces X-Signature as t=<unix-ms>,v1=<hex>', () => {
    assert(/^t=\d{13},v1=[0-9a-f]{64}$/.test(dryRun.signature), `unexpected signature format: ${dryRun.signature}`);
  });
  check('verifySignature accepts the signature the server computed', () => {
    // Signed over the transformed, pretty-printed payload, not over what we sent.
    eq(verifySignature(dryRun.transformedPayload, dryRun.signature, rotated.secret), true, 'verifySignature');
  });
  check('verifySignature rejects a tampered body', () => {
    let threw = false;
    try {
      verifySignature(`${dryRun.transformedPayload} `, dryRun.signature, rotated.secret);
    } catch {
      threw = true;
    }
    assert(threw, 'a tampered body was accepted');
  });
  check('verifySignature rejects a signature outside the 300s tolerance', () => {
    const stale = generateSignature(dryRun.transformedPayload, rotated.secret, Date.now() - 301_000);
    let threw = false;
    try {
      verifySignature(dryRun.transformedPayload, stale, rotated.secret);
    } catch (err) {
      threw = err.code === 'timestamp_expired';
    }
    assert(threw, 'a 301s-old signature was accepted (the server tolerance is 300s)');
  });

  await client.subscriptions.delete(projectId, subscription.id);
  await client.endpoints.delete(projectId, endpoint.id);
  await client.incomingSources.delete(projectId, source.id);

  console.log(`\n${passed} checks passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('\nFailures:');
    for (const f of failures) console.error(`  - ${f}`);
    process.exit(1);
  }
}

async function expectRejection(label, fn, assertOn) {
  try {
    await fn();
    failures.push(`${label}: the call resolved instead of rejecting`);
    console.log(`  FAIL ${label}\n         the call resolved instead of rejecting`);
  } catch (err) {
    try {
      assertOn(err);
      passed += 1;
      console.log(`  ok   ${label}`);
    } catch (assertErr) {
      failures.push(`${label}: ${assertErr.message}`);
      console.log(`  FAIL ${label}\n         ${assertErr.message}`);
    }
  }
}

main().catch((err) => {
  console.error(`\nsmoke check aborted: ${err.stack || err.message}`);
  process.exit(2);
});
