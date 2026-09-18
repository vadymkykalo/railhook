// Bootstrap helpers shared by every scenario's setup(). Each runs once per
// `k6 run`, outside the VU loop, so plain http.* calls (not the batched/async
// style used in the VU body) are fine here.
import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, LOAD_TEST_PASSWORD, RECEIVER_INTERNAL_URL, uniqueSuffix } from './config.js';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function assertOk(res, label, expectedStatus) {
  const ok = check(res, {
    [`${label} status is ${expectedStatus}`]: (r) => r.status === expectedStatus,
  });
  if (!ok) {
    fail(`${label} failed: HTTP ${res.status} ${res.body}`);
  }
  return res.json();
}

/**
 * Registers a fresh user+org (every k6 run gets its own tenant — cheaper and
 * more realistic than trying to share state across runs), creates a project
 * and a READ_WRITE API key scoped to it.
 *
 * With ACCESS_TOKEN and PROJECT_ID set, uses that existing account and project
 * instead: a deployment with a registration captcha (any production one) cannot
 * be registered against from a script.
 *
 * Returns { accessToken, authHeaders, projectId, apiKey }.
 */
export function bootstrapProject(namePrefix) {
  const suffix = uniqueSuffix();

  if (__ENV.ACCESS_TOKEN && __ENV.PROJECT_ID) {
    const authHeaders = {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${__ENV.ACCESS_TOKEN}`,
    };
    const keyRes = http.post(
      `${BASE_URL}/api/v1/projects/${__ENV.PROJECT_ID}/api-keys`,
      JSON.stringify({ name: `load-${namePrefix}-${suffix}`.slice(0, 100), scope: 'READ_WRITE' }),
      { headers: authHeaders }
    );
    const apiKey = assertOk(keyRes, 'create api key', 201);
    return {
      accessToken: __ENV.ACCESS_TOKEN,
      authHeaders,
      projectId: __ENV.PROJECT_ID,
      apiKey: apiKey.key,
    };
  }

  const email = `${namePrefix}-${suffix}@load-test.invalid`;

  const registerRes = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({
      email,
      password: LOAD_TEST_PASSWORD,
      fullName: `Load Test ${namePrefix}`,
      organizationName: `load-${namePrefix}-${suffix}`.slice(0, 100),
    }),
    { headers: JSON_HEADERS }
  );
  const auth = assertOk(registerRes, 'register', 201);
  const accessToken = auth.accessToken;
  const authHeaders = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${accessToken}`,
  };

  const projectRes = http.post(
    `${BASE_URL}/api/v1/projects`,
    JSON.stringify({ name: `load-${namePrefix}-${suffix}`.slice(0, 100) }),
    { headers: authHeaders }
  );
  const project = assertOk(projectRes, 'create project', 201);

  const keyRes = http.post(
    `${BASE_URL}/api/v1/projects/${project.id}/api-keys`,
    JSON.stringify({ name: `load-key-${suffix}`, scope: 'READ_WRITE' }),
    { headers: authHeaders }
  );
  const apiKey = assertOk(keyRes, 'create api key', 201);

  return {
    accessToken,
    authHeaders,
    projectId: project.id,
    apiKey: apiKey.key,
  };
}

/**
 * Creates an Endpoint pointed at the load-receiver (reachable from the
 * worker container over webhook-network — see RECEIVER_INTERNAL_URL) and a
 * Subscription binding it to eventType. Requires WEBHOOK_ALLOW_PRIVATE_IPS=true
 * on api+worker (see load/README.md) — the receiver's DNS name resolves to a
 * private Docker-bridge address, which SsrfProtectionCustomizer blocks by
 * default in every other context.
 */
export function createSubscribedEndpoint(ctx, eventType, { path = '/webhook', orderingEnabled = false } = {}) {
  const endpointRes = http.post(
    `${BASE_URL}/api/v1/projects/${ctx.projectId}/endpoints`,
    JSON.stringify({
      url: `${RECEIVER_INTERNAL_URL}${path}`,
      description: `load-test endpoint (${eventType})`,
      enabled: true,
    }),
    { headers: ctx.authHeaders }
  );
  const endpoint = assertOk(endpointRes, `create endpoint (${eventType})`, 201);

  const subRes = http.post(
    `${BASE_URL}/api/v1/projects/${ctx.projectId}/subscriptions`,
    JSON.stringify({
      endpointId: endpoint.id,
      eventType,
      enabled: true,
      orderingEnabled,
    }),
    { headers: ctx.authHeaders }
  );
  assertOk(subRes, `create subscription (${eventType})`, 201);

  return endpoint;
}
