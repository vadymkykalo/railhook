#!/usr/bin/env node
'use strict';

// No npm dependencies: it runs in a plain node:*-alpine container.

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
  // Which event type the forced failures are for; null means the next request whatever it is.
  failType: null,
  // Which receiver path the forced failures are for; null means the next request, whatever it is.
  failPath: null,
  received: [], // { seq, receivedAtMs, sentAtMs, latencyMs, type, headers }
};

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
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

function handleWebhook(req, res, body, requestPath) {
  const receivedAtMs = Date.now();

  let parsed = null;
  try {
    parsed = body ? JSON.parse(body) : null;
  } catch (e) {
    parsed = null;
  }

  const seq = parsed && parsed.data && parsed.data.seq !== undefined ? parsed.data.seq : (parsed ? parsed.seq : undefined);
  const sentAtMs = parsed && parsed.data && parsed.data.sentAtMs !== undefined ? parsed.data.sentAtMs : (parsed ? parsed.sentAtMs : undefined);

  const entry = {
    // The path, not the payload, tells two scenarios' deliveries apart.
    path: requestPath,
    seq,
    receivedAtMs,
    sentAtMs,
    latencyMs: typeof sentAtMs === 'number' ? receivedAtMs - sentAtMs : undefined,
    type: parsed ? parsed.type : undefined,
    deliveryAttempt: req.headers['x-webhook-attempt'] || req.headers['x-delivery-attempt'] || undefined,
    // The status this request was answered with: a seq answered 2xx twice is a duplicate
    // delivery, while a 503 followed by a 200 is a retry doing its job.
    status: 200,
  };
  state.received.push(entry);

  // Without the type guard a stranger's retry could consume the forced failure.
  if (state.failRemaining > 0
      && (!state.failPath || state.failPath === entry.path)
      && (!state.failType || state.failType === entry.type)) {
    state.failRemaining -= 1;
    entry.status = 500;
    sendJson(res, 500, { error: 'load-receiver: forced failure (fail-next)' });
    return;
  }

  if (state.mode === 'down') {
    entry.status = 503;
    sendJson(res, 503, { error: 'load-receiver: mode=down' });
    return;
  }

  if (state.mode === 'slow') {
    setTimeout(() => sendJson(res, 200, { ok: true, mode: 'slow' }), state.slowLatencyMs);
    return;
  }

  sendJson(res, 200, { ok: true });
}

// A retry ladder outlives its scenario, and two interleaved streams look like broken ordering, so a
// probe summarises its own path only.
function summarize({ path, type } = {}) {
  const received = state.received.filter(
    (r) => (!path || r.path === path) && (!type || r.type === type),
  );
  const seqs = received.map((r) => r.seq).filter((s) => typeof s === 'number');
  let outOfOrder = 0;
  for (let i = 1; i < seqs.length; i++) {
    if (seqs[i] < seqs[i - 1]) outOfOrder++;
  }
  const latencies = received.map((r) => r.latencyMs).filter((l) => typeof l === 'number').sort((a, b) => a - b);
  const p99 = latencies.length ? latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * 0.99))] : null;
  const p50 = latencies.length ? latencies[Math.floor(latencies.length * 0.5)] : null;
  const okCountBySeq = new Map();
  for (const r of received) {
    if (typeof r.seq === 'number' && r.status >= 200 && r.status < 300) {
      okCountBySeq.set(r.seq, (okCountBySeq.get(r.seq) || 0) + 1);
    }
  }
  let duplicateDeliveries = 0;
  for (const count of okCountBySeq.values()) {
    if (count > 1) duplicateDeliveries += count - 1;
  }
  return {
    totalReceived: received.length,
    distinctSeqs: new Set(seqs).size,
    seqsAnsweredOk: okCountBySeq.size,
    duplicateDeliveries,
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
      state.failPath = body.path || null;
      state.failType = body.type || null;
      console.log(`[load-receiver] will fail next ${state.failRemaining} request(s)`
        + (state.failPath ? ` to ${state.failPath}` : '')
        + (state.failType ? ` of type ${state.failType}` : ''));
      sendJson(res, 200, { failRemaining: state.failRemaining, failPath: state.failPath, failType: state.failType });
      return;
    }

    if (req.method === 'POST' && url.pathname === '/_control/reset') {
      state.mode = 'healthy';
      state.failRemaining = 0;
      state.failPath = null;
      state.failType = null;
      state.received = [];
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === 'GET' && url.pathname === '/_control/received') {
      const path = url.searchParams.get('path');
      const type = url.searchParams.get('type');
      sendJson(res, 200, state.received.filter(
        (r) => (!path || r.path === path) && (!type || r.type === type),
      ));
      return;
    }

    if (req.method === 'GET' && url.pathname === '/_control/summary') {
      sendJson(res, 200, summarize({
        path: url.searchParams.get('path'),
        type: url.searchParams.get('type'),
      }));
      return;
    }

    if (url.pathname.startsWith('/webhook')) {
      const body = await readBody(req);
      handleWebhook(req, res, body, url.pathname);
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
