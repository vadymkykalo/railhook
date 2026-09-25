// A forced retry with a burst behind it must still arrive in seq order. A correctness probe.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, RECEIVER_CONTROL_URL } from './lib/config.js';
import { bootstrapProject, createSubscribedEndpoint } from './lib/setup.js';

const orderingViolations = new Counter('ordering_violations');

const EVENT_TYPE = 'load.ordering_test';
// Its own path: a previous scenario may still be retrying into the same receiver.
const RECEIVER_PATH = '/webhook/ordering';
const BURST_SIZE = Number(__ENV.BURST_SIZE || 20);
// Retry rungs are jittered to 50–150%, so the 60s rung lands up to 90s out, plus a 10s poll.
const RETRY_WAIT_SECONDS = Number(__ENV.RETRY_WAIT_SECONDS || 150);

export const options = {
  scenarios: {
    ordering_probe: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: `${RETRY_WAIT_SECONDS + 60}s`,
    },
  },
  thresholds: {
    ordering_violations: ['count==0'],
  },
};

export function setup() {
  const ctx = bootstrapProject('ordering');
  createSubscribedEndpoint(ctx, EVENT_TYPE, { path: RECEIVER_PATH, orderingEnabled: true });

  const resetRes = http.post(`${RECEIVER_CONTROL_URL}/_control/reset`);
  if (resetRes.status !== 200) {
    console.warn(`load-receiver not reachable at ${RECEIVER_CONTROL_URL} — cannot run the ordering probe without it`);
  }

  return ctx;
}

function sendEvent(ctx, seq) {
  return http.post(
    `${BASE_URL}/api/v1/events`,
    JSON.stringify({ type: EVENT_TYPE, data: { seq, sentAtMs: Date.now() } }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': ctx.apiKey,
        'Idempotency-Key': `ordering-${seq}`,
      },
    }
  );
}

export default function (ctx) {
  check(sendEvent(ctx, 0), { 'seq 0 accepted': (r) => r.status === 201 });
  sleep(1);

  // Bound to this scenario's event type: a retry still draining from the scenario before would
  // otherwise swallow the forced failure, and the probe would prove nothing while passing.
  http.post(`${RECEIVER_CONTROL_URL}/_control/fail-next`, JSON.stringify({ count: 1, path: RECEIVER_PATH }), {
    headers: { 'Content-Type': 'application/json' },
  });

  check(sendEvent(ctx, 1), { 'seq 1 accepted': (r) => r.status === 201 });

  for (let seq = 2; seq < BURST_SIZE; seq++) {
    check(sendEvent(ctx, seq), { [`seq ${seq} accepted`]: (r) => r.status === 201 });
  }

  console.log(`sent burst of ${BURST_SIZE} ordered events (seq 1 forced to retry once); waiting ${RETRY_WAIT_SECONDS}s for the retry + backlog to drain`);
  sleep(RETRY_WAIT_SECONDS);
}

export function teardown() {
  // This scenario's own events only. Deliveries from the scenario before keep arriving after our
  // reset — their retry ladder outlives them — and two interleaved streams read as broken ordering.
  const summaryRes = http.get(`${RECEIVER_CONTROL_URL}/_control/summary?path=${RECEIVER_PATH}`);
  if (summaryRes.status !== 200) {
    console.warn('could not fetch load-receiver summary — cannot verify ordering');
    return;
  }
  const summary = summaryRes.json();
  console.log(`ordering result: ${JSON.stringify(summary)}`);
  if (summary.totalReceived === 0) {
    // Nothing arrived at all: the probe proves nothing, and a green run here would be a lie.
    orderingViolations.add(1);
    console.error(`NO DELIVERIES REACHED ${RECEIVER_PATH} — the ordering probe could not run (endpoint, worker or network)`);
    return;
  }
  if (summary.distinctSeqs < BURST_SIZE) {
    // The events still buffered behind the retry are exactly the ones that would have overtaken
    // it, so an in-order verdict over a partial burst says nothing.
    orderingViolations.add(1);
    console.error(`ONLY ${summary.distinctSeqs} OF ${BURST_SIZE} SEQUENCES REACHED ${RECEIVER_PATH} in ${RETRY_WAIT_SECONDS}s — the ordering probe did not see its own burst`);
    return;
  }
  if (!summary.inOrder) {
    orderingViolations.add(summary.outOfOrderTransitions);
    console.error(`ORDERING VIOLATED: ${summary.outOfOrderTransitions} out-of-order transition(s) across ${summary.distinctSeqs} sequence numbers — see GET ${RECEIVER_CONTROL_URL}/_control/received for the raw arrival log`);
  } else {
    console.log(`ordering held across ${summary.distinctSeqs} sequence numbers despite the induced retry`);
  }
}
