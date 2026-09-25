// Every value is overridable with `k6 run -e VAR=value` or an exported environment variable.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const RECEIVER_CONTROL_URL = __ENV.RECEIVER_CONTROL_URL || 'http://localhost:9000';

// The Endpoint's target: it must resolve inside webhook-network, where the worker runs.
export const RECEIVER_INTERNAL_URL = __ENV.RECEIVER_INTERNAL_URL || 'http://load-receiver:9000';

// Must meet the registration password policy: upper, lower, digit, special char.
export const LOAD_TEST_PASSWORD = __ENV.LOAD_TEST_PASSWORD || 'LoadTest!2026x';

export const TARGET_RPS = Number(__ENV.TARGET_RPS || 50);
export const DURATION = __ENV.DURATION || '2m';
export const FANOUT_N = Number(__ENV.FANOUT_N || 20);

export function uniqueSuffix() {
  // Date.now() keeps setup(), which runs outside any VU, unique across repeated runs.
  return `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}
