// Hours of steady ingestion, run beside load/scripts/monitor-soak.sh, to catch slow leaks.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, TARGET_RPS, DURATION, RECEIVER_CONTROL_URL } from './lib/config.js';
import { bootstrapProject, createSubscribedEndpoint } from './lib/setup.js';

const EVENT_TYPE = 'load.soak_test';

export const soakErrors = new Counter('soak_errors');

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: TARGET_RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(10, Math.ceil(TARGET_RPS / 2)),
      maxVUs: Math.max(30, TARGET_RPS * 3),
    },
  },
};

let seqCounter = 0;

export function setup() {
  const ctx = bootstrapProject('soak');
  createSubscribedEndpoint(ctx, EVENT_TYPE);
  http.post(`${RECEIVER_CONTROL_URL}/_control/reset`);
  console.log(`soak run starting: rate=${TARGET_RPS}/s duration=${DURATION} — start load/scripts/monitor-soak.sh now if you haven't`);
  return ctx;
}

export default function (ctx) {
  const seq = __VU * 1000000 + __ITER; seqCounter++;
  const res = http.post(
    `${BASE_URL}/api/v1/events`,
    JSON.stringify({ type: EVENT_TYPE, data: { seq, sentAtMs: Date.now() } }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': ctx.apiKey,
        'Idempotency-Key': `soak-${seq}`,
      },
    }
  );
  const ok = check(res, { 'ingest accepted (201)': (r) => r.status === 201 });
  if (!ok) soakErrors.add(1);
  sleep(0);
}

export function teardown() {
  const summaryRes = http.get(`${RECEIVER_CONTROL_URL}/_control/summary`);
  if (summaryRes.status === 200) {
    console.log(`soak run finished — load-receiver summary: ${summaryRes.body}`);
  }
  console.log('stop load/scripts/monitor-soak.sh now and inspect the CSV for connection/memory/Redis-key trends');
}
