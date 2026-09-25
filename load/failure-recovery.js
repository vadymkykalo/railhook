// An endpoint goes slow, then down, then recovers while traffic keeps flowing.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, RECEIVER_CONTROL_URL, TARGET_RPS } from './lib/config.js';
import { bootstrapProject, createSubscribedEndpoint } from './lib/setup.js';

const EVENT_TYPE = 'load.failure_recovery_test';

const PHASE_HEALTHY_SECONDS = Number(__ENV.PHASE_HEALTHY_SECONDS || 30);
const PHASE_SLOW_SECONDS = Number(__ENV.PHASE_SLOW_SECONDS || 30);
const PHASE_DOWN_SECONDS = Number(__ENV.PHASE_DOWN_SECONDS || 30);
const PHASE_RECOVER_SECONDS = Number(__ENV.PHASE_RECOVER_SECONDS || 60);
const TOTAL_SECONDS = PHASE_HEALTHY_SECONDS + PHASE_SLOW_SECONDS + PHASE_DOWN_SECONDS + PHASE_RECOVER_SECONDS;
const TRAFFIC_RPS = Number(__ENV.TRAFFIC_RPS || Math.min(TARGET_RPS, 10));

export const options = {
  scenarios: {
    traffic: {
      executor: 'constant-arrival-rate',
      exec: 'sendTraffic',
      rate: TRAFFIC_RPS,
      timeUnit: '1s',
      duration: `${TOTAL_SECONDS}s`,
      preAllocatedVUs: Math.max(10, TRAFFIC_RPS),
      maxVUs: Math.max(20, TRAFFIC_RPS * 2),
    },
    phase_control: {
      executor: 'shared-iterations',
      exec: 'controlPhases',
      vus: 1,
      iterations: 1,
      maxDuration: `${TOTAL_SECONDS + 10}s`,
    },
  },
};

let seqCounter = 0;

export function setup() {
  const ctx = bootstrapProject('failrecover');
  createSubscribedEndpoint(ctx, EVENT_TYPE);

  const resetRes = http.post(`${RECEIVER_CONTROL_URL}/_control/reset`);
  if (resetRes.status !== 200) {
    console.warn(`load-receiver not reachable at ${RECEIVER_CONTROL_URL} — this scenario needs it to actually go slow/down/recover; ingestion will still run but the failure injection won't happen`);
  }

  console.log(`failure-recovery phases (s): healthy=${PHASE_HEALTHY_SECONDS} slow=${PHASE_SLOW_SECONDS} down=${PHASE_DOWN_SECONDS} recover=${PHASE_RECOVER_SECONDS}`);
  return ctx;
}

function currentPhase(elapsedSeconds) {
  if (elapsedSeconds < PHASE_HEALTHY_SECONDS) return 'healthy';
  if (elapsedSeconds < PHASE_HEALTHY_SECONDS + PHASE_SLOW_SECONDS) return 'slow';
  if (elapsedSeconds < PHASE_HEALTHY_SECONDS + PHASE_SLOW_SECONDS + PHASE_DOWN_SECONDS) return 'down';
  return 'recovering';
}

export function sendTraffic(ctx) {
  const phaseSeq = seqCounter++; const seq = __VU * 1000000 + __ITER;
  // k6 has no "seconds since this scenario started" inside an iteration, so this is approximate;
  // the receiver's received-at timestamps are the authoritative record.
  const res = http.post(
    `${BASE_URL}/api/v1/events`,
    JSON.stringify({
      type: EVENT_TYPE,
      data: { seq, sentAtMs: Date.now(), phaseHint: currentPhase(phaseSeq / Math.max(TRAFFIC_RPS, 1)) },
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': ctx.apiKey,
        'Idempotency-Key': `failrecover-${seq}`,
      },
    }
  );
  check(res, { 'ingest accepted (201)': (r) => r.status === 201 });
}

export function controlPhases() {
  console.log('phase: healthy');
  sleep(PHASE_HEALTHY_SECONDS);

  http.post(`${RECEIVER_CONTROL_URL}/_control/mode`, JSON.stringify({ mode: 'slow', latencyMs: 5000 }), {
    headers: { 'Content-Type': 'application/json' },
  });
  console.log('phase: slow (5s added latency on every delivery attempt)');
  sleep(PHASE_SLOW_SECONDS);

  http.post(`${RECEIVER_CONTROL_URL}/_control/mode`, JSON.stringify({ mode: 'down' }), {
    headers: { 'Content-Type': 'application/json' },
  });
  console.log('phase: down (every delivery attempt gets a 503)');
  sleep(PHASE_DOWN_SECONDS);

  http.post(`${RECEIVER_CONTROL_URL}/_control/mode`, JSON.stringify({ mode: 'healthy' }), {
    headers: { 'Content-Type': 'application/json' },
  });
  console.log('phase: recovering (endpoint healthy again — watching the backlog drain)');
  sleep(PHASE_RECOVER_SECONDS);
}

export function teardown() {
  const summaryRes = http.get(`${RECEIVER_CONTROL_URL}/_control/summary`);
  if (summaryRes.status === 200) {
    console.log(`load-receiver summary: ${summaryRes.body}`);
  }
}
