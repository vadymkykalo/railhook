// Scenario: sustained ingestion at a target RPS.
//
// One project, one endpoint, one subscription. Every iteration POSTs an
// event through the standard API-key ingestion path
// (POST /api/v1/events -> EventController -> EventIngestService -> outbox).
// Each event is stamped with data.seq/data.sentAtMs; load-receiver on the
// other end turns those into a p99 end-to-end latency figure and an
// in-order/out-of-order count (see load/receiver/server.js).
//
// Usage:
//   k6 run load/ingest.js
//   k6 run -e TARGET_RPS=200 -e DURATION=5m load/ingest.js
//
// After the run, check http://localhost:9000/_control/summary for delivery
// stats and read outbox depth with load/scripts/outbox-depth.sh — see
// load/README.md "Reading results" for what to record where.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, TARGET_RPS, DURATION, RECEIVER_CONTROL_URL } from './lib/config.js';
import { bootstrapProject, createSubscribedEndpoint } from './lib/setup.js';

const EVENT_TYPE = 'load.ingest_test';

export const ingestErrors = new Counter('ingest_errors');
export const ingestLatency = new Trend('ingest_http_latency_ms');

export const options = {
  scenarios: {
    sustained_ingest: {
      executor: 'constant-arrival-rate',
      rate: TARGET_RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, Math.ceil(TARGET_RPS / 2)),
      maxVUs: Math.max(50, TARGET_RPS * 2),
    },
  },
  thresholds: {
    // Soft target, not a hard gate — see load/README.md "Target numbers".
    // Failing this doesn't fail CI (see load-tests.yml), it just surfaces in
    // the summary as a signal worth investigating.
    ingest_errors: ['count<1'],
  },
};

let seqCounter = 0;

export function setup() {
  const ctx = bootstrapProject('ingest');
  createSubscribedEndpoint(ctx, EVENT_TYPE);

  // Reset the receiver so a previous run's captures don't skew this one's
  // summary. Best-effort: if the receiver isn't up, ingestion still runs —
  // useful for measuring API-side throughput even without delivery numbers.
  const resetRes = http.post(`${RECEIVER_CONTROL_URL}/_control/reset`);
  if (resetRes.status !== 200) {
    console.warn(`load-receiver not reachable at ${RECEIVER_CONTROL_URL} (status ${resetRes.status}) — delivery-side metrics will be unavailable this run`);
  }

  return ctx;
}

export default function (ctx) {
  const seq = __VU * 1000000 + __ITER; seqCounter++;
  const res = http.post(
    `${BASE_URL}/api/v1/events`,
    JSON.stringify({
      type: EVENT_TYPE,
      data: { seq, sentAtMs: Date.now(), vu: __VU, iter: __ITER },
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': ctx.apiKey,
        'Idempotency-Key': `ingest-${__VU}-${__ITER}-${seq}`,
      },
    }
  );

  ingestLatency.add(res.timings.duration);

  const ok = check(res, {
    'ingest accepted (201)': (r) => r.status === 201,
    'not rate limited (429)': (r) => r.status !== 429,
  });
  if (!ok) ingestErrors.add(1);

  sleep(0); // constant-arrival-rate paces iterations; no extra sleep needed
}

export function teardown() {
  const summaryRes = http.get(`${RECEIVER_CONTROL_URL}/_control/summary`);
  if (summaryRes.status === 200) {
    console.log(`load-receiver summary: ${summaryRes.body}`);
  }
}
