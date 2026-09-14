#!/usr/bin/env node
'use strict';

/**
 * load-receiver: a stand-in "customer server" for the k6 load harness.
 *
 * It is the target the platform delivers webhooks to. Unlike a real customer
 * endpoint, its behaviour is remote-controlled over a small HTTP control API,
 * so a k6 scenario can flip it between healthy / slow / down mid-run (for
 * the failure-recovery scenario) or make it fail the first N attempts for a
 * given delivery (to force the retries that back up an ordered subscription
 * in the ordering scenario — see load/ordering.js).
 *
 * Zero npm dependencies on purpose: it runs as `node load/receiver/server.js`
 * inside a plain `node:*-alpine` container (see load/docker-compose.load.yml)
 * with nothing to `npm install`.
 *
 * Routes:
 *   ANY  /webhook*            - delivery target. Behaviour depends on current mode.
 *   POST /_control/mode       - { mode: "healthy"|"slow"|"down", latencyMs? }
 *   POST /_control/fail-next  - { count: N } - next N /webhook requests return 500
 *   POST /_control/reset      - clears the received-request log and fail/mode state
 *   GET  /_control/received   - JSON array of everything /webhook has seen so far
 *   GET  /_control/summary    - counts + basic ordering stats over the received log
 *   GET  /_control/health     - liveness probe for the control API itself
 */

const http = require('http');

const PORT = Number(process.env.PORT || 9000);

// Each slow response holds a socket and a timer for this long, so the control API will not
// accept a delay that would pile them up for longer than any scenario waits.
const MAX_SLOW_LATENCY_MS = 60000;

function validLatency(value) {
  return typeof value === 'number' && value >= 0 && value <= MAX_SLOW_LATENCY_MS;
}

const configuredLatency = Number(process.env.DEFAULT_SLOW_LATENCY_MS || 3000);

const state = {
  mode: 'healthy', // healthy | slow | down
  slowLatencyMs: validLatency(configuredLatency) ? configuredLatency : 3000,
  failRemaining: 0,
  received: [], // { seq, receivedAtMs, sentAtMs, latencyMs, type, headers }
};

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      // Guard against a runaway body in a load test.
      if (data.length > 5 * 1024 * 1024) {
        reject(new Error('body too large'));
        req.destroy();
      }
    });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) });
  res.end(payload);
}

function handleWebhook(req, res, body) {
  const receivedAtMs = Date.now();

  let parsed = null;
  try {
    parsed = body ? JSON.parse(body) : null;
  } catch (e) {
    // Not all deliveries are guaranteed valid JSON in general; the load
    // harness always sends JSON, so this is logged rather than fatal.
    parsed = null;
  }

  // The load harness stamps outgoing events with data.seq and data.sentAtMs
  // so the receiver can compute an end-to-end latency proxy and check
  // ordering without needing to know anything about the platform's internal
  // sequence numbers (see load/lib/setup.js and load/ordering.js).
  const seq = parsed && parsed.data ? parsed.data.seq : undefined;
  const sentAtMs = parsed && parsed.data ? parsed.data.sentAtMs : undefined;

  state.received.push({
    seq,
    receivedAtMs,
    sentAtMs,
    latencyMs: typeof sentAtMs === 'number' ? receivedAtMs - sentAtMs : undefined,
    type: parsed ? parsed.type : undefined,
    deliveryAttempt: req.headers['x-webhook-attempt'] || req.headers['x-delivery-attempt'] || undefined,
  });

  if (state.failRemaining > 0) {
    state.failRemaining -= 1;
    sendJson(res, 500, { error: 'load-receiver: forced failure (fail-next)' });
    return;
  }

  if (state.mode === 'down') {
    sendJson(res, 503, { error: 'load-receiver: mode=down' });
    return;
  }

  if (state.mode === 'slow') {
    setTimeout(() => sendJson(res, 200, { ok: true, mode: 'slow' }), state.slowLatencyMs);
    return;
  }

  sendJson(res, 200, { ok: true });
}

function summarize() {
  const seqs = state.received.map((r) => r.seq).filter((s) => typeof s === 'number');
  let outOfOrder = 0;
  for (let i = 1; i < seqs.length; i++) {
    if (seqs[i] < seqs[i - 1]) outOfOrder++;
  }
  const latencies = state.received.map((r) => r.latencyMs).filter((l) => typeof l === 'number').sort((a, b) => a - b);
  const p99 = latencies.length ? latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * 0.99))] : null;
  const p50 = latencies.length ? latencies[Math.floor(latencies.length * 0.5)] : null;
  return {
    totalReceived: state.received.length,
    distinctSeqs: seqs.length,
    outOfOrderTransitions: outOfOrder,
    inOrder: outOfOrder === 0,
    latencyMsP50: p50,
    latencyMsP99: p99,
    currentMode: state.mode,
  };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  try {
    if (req.method === 'GET' && url.pathname === '/_control/health') {
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === 'POST' && url.pathname === '/_control/mode') {
      const body = JSON.parse((await readBody(req)) || '{}');
      if (!['healthy', 'slow', 'down'].includes(body.mode)) {
        sendJson(res, 400, { error: 'mode must be healthy|slow|down' });
        return;
      }
      if (body.latencyMs !== undefined && !validLatency(body.latencyMs)) {
        sendJson(res, 400, { error: `latencyMs must be a number from 0 to ${MAX_SLOW_LATENCY_MS}` });
        return;
      }
      state.mode = body.mode;
      if (body.latencyMs !== undefined) state.slowLatencyMs = body.latencyMs;
      console.log(`[load-receiver] mode -> ${state.mode} (slowLatencyMs=${state.slowLatencyMs})`);
      sendJson(res, 200, { mode: state.mode, slowLatencyMs: state.slowLatencyMs });
      return;
    }

    if (req.method === 'POST' && url.pathname === '/_control/fail-next') {
      const body = JSON.parse((await readBody(req)) || '{}');
      state.failRemaining = Number(body.count || 0);
      console.log(`[load-receiver] will fail next ${state.failRemaining} request(s)`);
      sendJson(res, 200, { failRemaining: state.failRemaining });
      return;
    }

    if (req.method === 'POST' && url.pathname === '/_control/reset') {
      state.mode = 'healthy';
      state.failRemaining = 0;
      state.received = [];
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === 'GET' && url.pathname === '/_control/received') {
      sendJson(res, 200, state.received);
      return;
    }

    if (req.method === 'GET' && url.pathname === '/_control/summary') {
      sendJson(res, 200, summarize());
      return;
    }

    if (url.pathname.startsWith('/webhook')) {
      const body = await readBody(req);
      handleWebhook(req, res, body);
      return;
    }

    sendJson(res, 404, { error: 'not found' });
  } catch (err) {
    // The detail goes to the receiver's own log, not back to whoever sent the request.
    console.error('[load-receiver] request failed', err);
    sendJson(res, 500, { error: 'load-receiver: request failed' });
  }
});

server.listen(PORT, () => {
  console.log(`[load-receiver] listening on :${PORT}`);
});
