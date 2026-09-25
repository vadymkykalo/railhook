// One event, FANOUT_N subscribed endpoints: does the receiver see EVENTS_TO_SEND * FANOUT_N?
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, FANOUT_N, RECEIVER_CONTROL_URL } from './lib/config.js';
import { bootstrapProject, createSubscribedEndpoint } from './lib/setup.js';

const EVENT_TYPE = 'load.fanout_test';
const EVENTS_TO_SEND = Number(__ENV.EVENTS_TO_SEND || 5);
const SETTLE_SECONDS = Number(__ENV.SETTLE_SECONDS || 30);

export const options = {
  scenarios: {
    fanout_burst: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: EVENTS_TO_SEND,
      maxDuration: '2m',
    },
  },
};

export function setup() {
  const ctx = bootstrapProject('fanout');

  const endpoints = [];
  for (let i = 0; i < FANOUT_N; i++) {
    endpoints.push(createSubscribedEndpoint(ctx, EVENT_TYPE, { path: `/webhook?ep=${i}` }));
  }

  const resetRes = http.post(`${RECEIVER_CONTROL_URL}/_control/reset`);
  if (resetRes.status !== 200) {
    console.warn(`load-receiver not reachable at ${RECEIVER_CONTROL_URL} — cannot verify fan-out counts this run`);
  }

  console.log(`fanout setup: ${endpoints.length} endpoints subscribed to ${EVENT_TYPE}, sending ${EVENTS_TO_SEND} events (expecting ${EVENTS_TO_SEND * FANOUT_N} deliveries)`);
  return ctx;
}

export default function (ctx) {
  const seq = __ITER;
  const res = http.post(
    `${BASE_URL}/api/v1/events`,
    JSON.stringify({
      type: EVENT_TYPE,
      data: { seq, sentAtMs: Date.now() },
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': ctx.apiKey,
        'Idempotency-Key': `fanout-${seq}`,
      },
    }
  );

  check(res, {
    'fanout event accepted (201)': (r) => r.status === 201,
    [`deliveriesCreated === ${FANOUT_N}`]: (r) => {
      if (r.status !== 201) return false;
      const body = r.json();
      return body.deliveriesCreated === FANOUT_N;
    },
  });
}

export function teardown() {
  sleep(SETTLE_SECONDS);
  const summaryRes = http.get(`${RECEIVER_CONTROL_URL}/_control/summary`);
  if (summaryRes.status !== 200) {
    console.warn('could not fetch load-receiver summary for fan-out verification');
    return;
  }
  const summary = summaryRes.json();
  const expected = EVENTS_TO_SEND * FANOUT_N;
  console.log(`fanout result: expected ${expected} deliveries, load-receiver saw ${summary.totalReceived} (p50=${summary.latencyMsP50}ms p99=${summary.latencyMsP99}ms)`);
}
