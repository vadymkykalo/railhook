// The receiver's control plane, driven over HTTP the way the k6 scenarios drive it.
//
// One receiver serves every scenario in a run, and a scenario's deliveries keep arriving after
// the next one has reset — a retry ladder outlives the scenario that started it. Both guards
// below exist because that bled across: an ordering probe read another scenario's stream as its
// own and reported 135 out-of-order transitions, and a forced failure could be spent on a
// stranger's retry, leaving the probe green having induced nothing.
//
// Run: node --test load/receiver/server.test.js
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const path = require('node:path');

const PORT = 9411;
const BASE = `http://127.0.0.1:${PORT}`;
let child;

async function waitForHealth() {
  for (let i = 0; i < 100; i++) {
    try {
      const res = await fetch(`${BASE}/_control/health`);
      if (res.ok) return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error('load-receiver did not start');
}

before(async () => {
  child = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    env: { ...process.env, PORT: String(PORT) },
    stdio: 'ignore',
  });
  await waitForHealth();
});

after(() => child?.kill());

const control = (path, body) =>
  fetch(`${BASE}/_control/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  });

const deliver = (type, seq, path = '/webhook') =>
  fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, data: { seq, sentAtMs: Date.now() } }),
  });

const summary = async (query = '') => (await fetch(`${BASE}/_control/summary${query}`)).json();

test('a summary for one path ignores another scenario draining through the same receiver', async () => {
  await control('reset');
  // Ordering probe on its own path: 0, 1, 2 in order. Another scenario's retries interleave on
  // the default path, out of order — and carry no event type, exactly like a real delivery.
  await deliver(undefined, 0, '/webhook/ordering');
  await deliver(undefined, 900);
  await deliver(undefined, 1, '/webhook/ordering');
  await deliver(undefined, 12);
  await deliver(undefined, 2, '/webhook/ordering');
  await deliver(undefined, 700);

  const mine = await summary('?path=/webhook/ordering');
  assert.equal(mine.totalReceived, 3);
  assert.equal(mine.distinctSeqs, 3);
  assert.equal(mine.outOfOrderTransitions, 0);
  assert.equal(mine.inOrder, true);

  const everything = await summary('');
  assert.equal(everything.totalReceived, 6);
  assert.ok(everything.outOfOrderTransitions > 0, 'the mixed stream is what used to fail the probe');
});

test('duplicates are counted per path', async () => {
  await control('reset');
  await deliver(undefined, 5, '/webhook/ordering');
  await deliver(undefined, 5, '/webhook/ordering');
  await deliver(undefined, 5);

  assert.equal((await summary('?path=/webhook/ordering')).duplicateDeliveries, 1);
  assert.equal((await summary('?path=/webhook')).duplicateDeliveries, 0);
});

test('a forced failure waits for the path it was asked for', async () => {
  await control('reset');
  await control('fail-next', { count: 1, path: '/webhook/ordering' });

  const stranger = await deliver(undefined, 1);
  assert.equal(stranger.status, 200, 'another scenario must not spend the forced failure');

  const mine = await deliver(undefined, 1, '/webhook/ordering');
  assert.equal(mine.status, 500);

  const next = await deliver(undefined, 2, '/webhook/ordering');
  assert.equal(next.status, 200, 'exactly one failure was asked for');
});

test('without a path, the next request takes the failure, as before', async () => {
  await control('reset');
  await control('fail-next', { count: 1 });
  assert.equal((await deliver(undefined, 1)).status, 500);
  assert.equal((await deliver(undefined, 2)).status, 200);
});

test('reset clears a forced failure that was never spent', async () => {
  await control('fail-next', { count: 1, path: '/webhook/ordering' });
  await control('reset');
  assert.equal((await deliver(undefined, 1, '/webhook/ordering')).status, 200);
  assert.equal((await summary('?path=/webhook/ordering')).totalReceived, 1);
});
