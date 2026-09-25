// Incoming traffic with the destination down for PHASE_DOWN_SECONDS. Passes when every accepted
// webhook was forwarded exactly once.

import http from 'k6/http';
import crypto from 'k6/crypto';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, RECEIVER_CONTROL_URL, RECEIVER_INTERNAL_URL, uniqueSuffix } from './lib/config.js';
import { bootstrapProject } from './lib/setup.js';

const TRAFFIC_RPS = Number(__ENV.TRAFFIC_RPS || 5);
const DURATION_SECONDS = Number(__ENV.DURATION_SECONDS || 180);
const PHASE_HEALTHY_SECONDS = Number(__ENV.PHASE_HEALTHY_SECONDS || 60);
const PHASE_DOWN_SECONDS = Number(__ENV.PHASE_DOWN_SECONDS || 60);
const SETTLE_SECONDS = Number(__ENV.SETTLE_SECONDS || 240);

const accepted = new Counter('ingress_accepted');
const rejected = new Counter('ingress_rejected');

export const options = {
  scenarios: {
    traffic: {
      executor: 'constant-arrival-rate',
      exec: 'traffic',
      rate: TRAFFIC_RPS,
      timeUnit: '1s',
      duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: Math.max(5, TRAFFIC_RPS * 2),
    },
    phase_control: {
      executor: 'shared-iterations',
      exec: 'phaseControl',
      vus: 1,
      iterations: 1,
      maxDuration: `${DURATION_SECONDS + 60}s`,
    },
  },
  thresholds: {
    ingress_rejected: ['count==0'],
  },
  teardownTimeout: `${SETTLE_SECONDS + 60}s`,
};

export function setup() {
  const ctx = bootstrapProject('incoming');
  // A signing key, so it comes from a CSPRNG rather than the Math.random() uniqueSuffix() uses.
  const secret = `load-${crypto.hexEncode(crypto.randomBytes(32))}`;

  const sourceRes = http.post(
    `${BASE_URL}/api/v1/projects/${ctx.projectId}/incoming-sources`,
    JSON.stringify({
      name: `load-incoming-${uniqueSuffix()}`.slice(0, 100),
      providerType: 'GENERIC',
      verificationMode: 'HMAC_GENERIC',
      hmacSecret: secret,
      hmacHeaderName: 'X-Signature',
    }),
    { headers: ctx.authHeaders }
  );
  check(sourceRes, { 'create source 201': (r) => r.status === 201 }) ||
    console.error(`create source: ${sourceRes.status} ${sourceRes.body}`);
  const source = sourceRes.json();

  const destRes = http.post(
    `${BASE_URL}/api/v1/projects/${ctx.projectId}/incoming-sources/${source.id}/destinations`,
    JSON.stringify({ url: `${RECEIVER_INTERNAL_URL}/webhook?dir=in`, enabled: true }),
    { headers: ctx.authHeaders }
  );
  check(destRes, { 'create destination 201': (r) => r.status === 201 }) ||
    console.error(`create destination: ${destRes.status} ${destRes.body}`);

  http.post(`${RECEIVER_CONTROL_URL}/_control/reset`);
  const ingressUrl = source.ingressUrl && source.ingressUrl.startsWith('http')
    ? source.ingressUrl
    : `${BASE_URL}/ingress/${source.ingressPathToken}`;
  return { ingressUrl, secret, sourceId: source.id, projectId: ctx.projectId };
}

export function traffic(data) {
  // Unique per request: the generic raw-hex signature covers only the body, so two identical
  // bodies would be refused by replay detection as the same signature.
  const seq = Date.now() * 1000 + Math.floor(Math.random() * 1000);
  const body = JSON.stringify({ type: 'load.incoming', data: { seq, sentAtMs: Date.now(), vu: __VU, iter: __ITER } });
  const signature = crypto.hmac('sha256', data.secret, body, 'hex');
  const res = http.post(data.ingressUrl, body, {
    headers: { 'Content-Type': 'application/json', 'X-Signature': signature },
  });
  if (res.status >= 200 && res.status < 300) {
    accepted.add(1);
  } else {
    rejected.add(1, { status: String(res.status) });
    console.warn(`ingress ${res.status}: ${String(res.body).slice(0, 200)}`);
  }
}

export function phaseControl() {
  sleep(PHASE_HEALTHY_SECONDS);
  http.post(`${RECEIVER_CONTROL_URL}/_control/mode`, JSON.stringify({ mode: 'down' }), {
    headers: { 'Content-Type': 'application/json' },
  });
  console.log(`receiver DOWN for ${PHASE_DOWN_SECONDS}s`);
  sleep(PHASE_DOWN_SECONDS);
  http.post(`${RECEIVER_CONTROL_URL}/_control/mode`, JSON.stringify({ mode: 'healthy' }), {
    headers: { 'Content-Type': 'application/json' },
  });
  console.log('receiver HEALTHY');
}

export function teardown() {
  console.log(`settling ${SETTLE_SECONDS}s for retries to drain`);
  sleep(SETTLE_SECONDS);
  const summary = http.get(`${RECEIVER_CONTROL_URL}/_control/summary`);
  console.log(`receiver summary: ${summary.body}`);
}
