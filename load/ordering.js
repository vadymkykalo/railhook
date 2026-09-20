// Scenario: ordered deliveries under backlog.
//
// This is the black-box reproduction of the condition that silently
// disengages FIFO ordering: a subscription with orderingEnabled
// forces one delivery to retry (by having load-receiver fail exactly once),
// then fires a burst of successor events immediately behind it. If ordering
// holds, load-receiver should see them arrive in seq order once the retried
// one finally succeeds — the successors should sit buffered
// (OrderingBufferService, worker-side) rather than racing ahead. If it
// doesn't hold, the receiver sees the successors before the retried one,
// which is caught by /_control/summary's outOfOrderTransitions count.
//
// This is a correctness probe more than a throughput one — it runs a small,
// fixed burst rather than sustained load. Run load/ingest.js or
// load/fanout.js first if you also want ordering-under-backlog numbers
// alongside general throughput ones.
//
// Usage:
//   k6 run load/ordering.js
//   k6 run -e BURST_SIZE=50 -e RETRY_WAIT_SECONDS=90 load/ordering.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, RECEIVER_CONTROL_URL } from './lib/config.js';
import { bootstrapProject, createSubscribedEndpoint } from './lib/setup.js';

// A non-zero count here after teardown means FIFO ordering broke down under
// the induced-retry backlog — the regression this whole scenario exists to
// catch. The threshold below turns that into a non-zero `k6 run`
// exit code so this is CI-checkable, not just eyeballed from log output.
const orderingViolations = new Counter('ordering_violations');

const EVENT_TYPE = 'load.ordering_test';
// A receiver path of this scenario's own. The delivered body carries the event's payload and no
// event type, so the path is the only thing that tells these deliveries apart from the ones a
// previous scenario is still retrying into the same receiver.
const RECEIVER_PATH = '/webhook/ordering';
const BURST_SIZE = Number(__ENV.BURST_SIZE || 20);
// Must outlast the *worst case* of the subscription's first retry delay, not its nominal
// value: RetryLadder.nextRetryAt jitters every rung to 50–150% of it, so the default Outgoing
// first rung of 60s lands anywhere up to 90s out, and RetrySchedulerService then picks it up on
// its next poll (retry.scheduler.poll-interval-ms, 10s). 90s covered the rung and not the
// jitter, so the probe regularly stopped watching before the retry it induced had happened and
// reported on two of its fifteen sequences. Bump this if your subscription uses a longer ladder.
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
  // seq 0: a normal, healthy delivery — establishes the ordering cursor.
  check(sendEvent(ctx, 0), { 'seq 0 accepted': (r) => r.status === 201 });
  sleep(1);

  // Force exactly one failure so the *next* delivery attempt (seq 1) fails
  // and goes to retry, opening the gap the rest of the burst arrives into.
  // Bound to this scenario's event type: a retry still draining from the scenario before would
  // otherwise swallow the forced failure, and the probe would prove nothing while passing.
  http.post(`${RECEIVER_CONTROL_URL}/_control/fail-next`, JSON.stringify({ count: 1, path: RECEIVER_PATH }), {
    headers: { 'Content-Type': 'application/json' },
  });

  check(sendEvent(ctx, 1), { 'seq 1 accepted': (r) => r.status === 201 });

  // Fire the rest of the burst immediately behind it, before seq 1's retry
  // has had a chance to succeed. If ordering holds, none of these should
  // reach load-receiver before seq 1's retried delivery does.
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
    // The same lie, one step along: the events still buffered behind the retry are exactly the
    // ones that would have overtaken it, so an in-order verdict over a partial burst says
    // nothing about the thing this probe exists to catch. Either the wait is too short for the
    // ladder (see RETRY_WAIT_SECONDS) or the backlog never drained.
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
