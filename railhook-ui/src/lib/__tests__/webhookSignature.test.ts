import { createHmac } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { exampleHeaders, readHeaderValue, verifySignature, PROVIDERS, type Provider } from '../webhookSignature';

/** Vectors computed with Node's HMAC, never the code under test, so a scheme bug cannot agree with itself. */
const BODY = '{"id":"evt_1","type":"order.completed","amount":4200}';
const NOW_S = 1_760_000_000;
const NOW_MS = NOW_S * 1000;

const hmac = (key: string | Buffer, data: string) => createHmac('sha256', key).update(data, 'utf8');

describe('Standard Webhooks', () => {
  const keyBytes = Buffer.from('a-standard-webhooks-key-32-bytes');
  const secret = `whsec_${keyBytes.toString('base64')}`;
  const id = 'msg_2Lh9KZ';
  const ts = String(NOW_S);
  const sig = hmac(keyBytes, `${id}.${ts}.${BODY}`).digest('base64');
  const headers = { 'webhook-id': id, 'webhook-timestamp': ts, 'webhook-signature': `v1,${sig}` };

  it('accepts a signature made with the decoded whsec_ key over id.timestamp.body', async () => {
    const result = await verifySignature({ provider: 'standard', payload: BODY, secret, headers, now: NOW_MS });
    expect(result).toMatchObject({ status: 'valid', expected: `v1,${sig}` });
  });

  it('accepts any of several space-separated signatures, as during a secret rotation', async () => {
    const result = await verifySignature({
      provider: 'standard',
      payload: BODY,
      secret,
      headers: { ...headers, 'webhook-signature': `v1,${Buffer.from('nope').toString('base64')} v1,${sig}` },
      now: NOW_MS,
    });
    expect(result.status).toBe('valid');
  });

  it('refuses a changed body', async () => {
    const result = await verifySignature({ provider: 'standard', payload: `${BODY} `, secret, headers, now: NOW_MS });
    expect(result.status).toBe('invalid');
  });

  it('refuses a signature made with the whsec_ string itself rather than the key it encodes', async () => {
    const wrong = hmac(secret, `${id}.${ts}.${BODY}`).digest('base64');
    const result = await verifySignature({
      provider: 'standard', payload: BODY, secret, headers: { ...headers, 'webhook-signature': `v1,${wrong}` }, now: NOW_MS,
    });
    expect(result.status).toBe('invalid');
  });

  it('reports a whsec_ secret that is not base64', async () => {
    const result = await verifySignature({ provider: 'standard', payload: BODY, secret: 'whsec_***', headers, now: NOW_MS });
    expect(result).toMatchObject({ status: 'malformed', reason: 'secret' });
  });

  it('flags a timestamp outside the five-minute window, while still checking the signature', async () => {
    const result = await verifySignature({ provider: 'standard', payload: BODY, secret, headers, now: NOW_MS + 10 * 60_000 });
    expect(result).toMatchObject({
      status: 'valid',
      timestamp: { withinTolerance: false, toleranceSeconds: 300, ageSeconds: 600 },
    });
  });
});

describe('Railhook X-Signature', () => {
  const secret = 'railhook-endpoint-secret';
  const t = String(NOW_MS);
  const sig = hmac(secret, `${t}.${BODY}`).digest('hex');

  it('accepts hex HMAC over t.body with t in milliseconds', async () => {
    const result = await verifySignature({
      provider: 'railhook', payload: BODY, secret, headers: { 'X-Signature': `t=${t},v1=${sig}` }, now: NOW_MS,
    });
    expect(result).toMatchObject({ status: 'valid', expected: `t=${t},v1=${sig}`, timestamp: { withinTolerance: true } });
  });

  it('accepts the second v1 of a rotation', async () => {
    const result = await verifySignature({
      provider: 'railhook', payload: BODY, secret, headers: { 'X-Signature': `t=${t},v1=${'0'.repeat(64)},v1=${sig}` }, now: NOW_MS,
    });
    expect(result.status).toBe('valid');
  });
});

describe('Stripe', () => {
  const secret = 'whsec_test_stripe_secret';
  const t = String(NOW_S);
  // Stripe keys the HMAC with the whole secret, whsec_ included.
  const sig = hmac(secret, `${t}.${BODY}`).digest('hex');

  it('accepts hex HMAC over t.body keyed with the secret as written', async () => {
    const result = await verifySignature({
      provider: 'stripe', payload: BODY, secret, headers: { 'Stripe-Signature': `t=${t},v1=${sig},v0=deadbeef` }, now: NOW_MS,
    });
    expect(result).toMatchObject({ status: 'valid', timestamp: { withinTolerance: true, toleranceSeconds: 300 } });
  });

  it('refuses a header without t or v1', async () => {
    const result = await verifySignature({
      provider: 'stripe', payload: BODY, secret, headers: { 'Stripe-Signature': `v1=${sig}` }, now: NOW_MS,
    });
    expect(result).toMatchObject({ status: 'malformed', reason: 'header' });
  });

  it('refuses a signature for another body', async () => {
    const result = await verifySignature({
      provider: 'stripe', payload: '{}', secret, headers: { 'Stripe-Signature': `t=${t},v1=${sig}` }, now: NOW_MS,
    });
    expect(result.status).toBe('invalid');
  });
});

describe('GitHub', () => {
  const secret = "It's a Secret to Everybody";
  const payload = 'Hello, World!';
  const documented = 'sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17';

  it('matches the example in GitHub’s documentation', async () => {
    expect(`sha256=${hmac(secret, payload).digest('hex')}`).toBe(documented);
    const result = await verifySignature({
      provider: 'github', payload, secret, headers: { 'X-Hub-Signature-256': documented }, now: NOW_MS,
    });
    expect(result).toMatchObject({ status: 'valid', expected: documented });
    expect(result.status === 'valid' && result.timestamp).toBeFalsy();
  });

  it('requires the sha256= prefix', async () => {
    const result = await verifySignature({
      provider: 'github', payload, secret, headers: { 'X-Hub-Signature-256': documented.slice(7) }, now: NOW_MS,
    });
    expect(result).toMatchObject({ status: 'malformed', reason: 'header' });
  });
});

describe('Shopify', () => {
  const secret = 'shpss_shopify_app_secret';
  const sig = hmac(secret, BODY).digest('base64');

  it('accepts base64 HMAC over the raw body', async () => {
    const result = await verifySignature({
      provider: 'shopify', payload: BODY, secret, headers: { 'X-Shopify-Hmac-Sha256': sig }, now: NOW_MS,
    });
    expect(result).toMatchObject({ status: 'valid', expected: sig });
  });
});

describe('Slack', () => {
  const secret = '8f742231b10e8888abcd99yyyzzz85a5';
  const ts = String(NOW_S);
  const body = 'token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&command=%2Fweather&text=94070';
  const sig = `v0=${hmac(secret, `v0:${ts}:${body}`).digest('hex')}`;

  it('accepts v0= hex HMAC over v0:timestamp:body', async () => {
    const result = await verifySignature({
      provider: 'slack', payload: body, secret,
      headers: { 'X-Slack-Signature': sig, 'X-Slack-Request-Timestamp': ts }, now: NOW_MS,
    });
    expect(result).toMatchObject({ status: 'valid', expected: sig, timestamp: { withinTolerance: true } });
  });

  it('reports a timestamp that is not a number', async () => {
    const result = await verifySignature({
      provider: 'slack', payload: body, secret,
      headers: { 'X-Slack-Signature': sig, 'X-Slack-Request-Timestamp': 'yesterday' }, now: NOW_MS,
    });
    expect(result).toMatchObject({ status: 'malformed', reason: 'timestamp' });
  });
});

describe('input', () => {
  it('asks for what is missing before computing anything', async () => {
    const result = await verifySignature({ provider: 'slack', payload: 'x', secret: '', headers: {}, now: NOW_MS });
    expect(result).toEqual({ status: 'incomplete' });
  });

  it('reads a header pasted with its name in front', () => {
    expect(readHeaderValue('Stripe-Signature: t=1,v1=ab', 'Stripe-Signature')).toBe('t=1,v1=ab');
    expect(readHeaderValue('  x-hub-signature-256:sha256=ab ', 'X-Hub-Signature-256')).toBe('sha256=ab');
    expect(readHeaderValue('t=1,v1=ab', 'Stripe-Signature')).toBe('t=1,v1=ab');
  });
});

describe('the example', () => {
  it.each(PROVIDERS.map((p) => p.id))('%s: produces headers that verify', async (provider: Provider) => {
    const secret = provider === 'standard' ? `whsec_${Buffer.from('example-key').toString('base64')}` : 'example-secret';
    const headers = await exampleHeaders(provider, BODY, secret, NOW_MS);
    const result = await verifySignature({ provider, payload: BODY, secret, headers, now: NOW_MS });
    expect(result.status).toBe('valid');
  });
});
