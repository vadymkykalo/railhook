import { verifyStandardWebhook } from '../webhooks';
import { RailhookError } from '../errors';
import * as crypto from 'crypto';

/**
 * The point of this scheme is that a receiver can verify with a library they already have,
 * so the first test is the reference algorithm rather than a round-trip against ourselves —
 * a round-trip would only prove we agree with our own bug.
 */
describe('verifyStandardWebhook', () => {
  const id = 'msg_p5jXN8AQM9LWM0D4loKWxJek';
  const payload = '{"test": 2432232314}';
  const secretB64 = 'MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw';
  const sharedSecret = `whsec_${secretB64}`;

  /** Exactly what the reference libraries do: hmac(b64decode(secret), `${id}.${ts}.${body}`). */
  const sign = (ts: number, secret = secretB64) =>
    crypto
      .createHmac('sha256', Buffer.from(secret, 'base64'))
      .update(`${id}.${ts}.${payload}`)
      .digest('base64');

  const headers = (ts: number, signature: string) => ({
    'webhook-id': id,
    'webhook-timestamp': String(ts),
    'webhook-signature': signature,
  });

  it('accepts a signature produced the way the reference libraries produce one', () => {
    const ts = Math.floor(Date.now() / 1000);
    expect(verifyStandardWebhook(payload, headers(ts, `v1,${sign(ts)}`), sharedSecret)).toBe(true);
  });

  it('accepts either signature during a secret rotation', () => {
    const ts = Math.floor(Date.now() / 1000);
    const retired = 'b2xkLXNlY3JldC1ieXRlcy1oZXJlLXBhZGRpbmc=';
    // What Railhook sends through the grace window: one signature per valid secret.
    const header = `v1,${sign(ts)} v1,${sign(ts, retired)}`;

    expect(verifyStandardWebhook(payload, headers(ts, header), sharedSecret)).toBe(true);
    expect(verifyStandardWebhook(payload, headers(ts, header), `whsec_${retired}`)).toBe(true);
  });

  it('rejects a replayed request even though its signature is still valid', () => {
    // A signature over a fixed body never expires by itself, so without the timestamp check
    // a captured request stays replayable for as long as the secret lives.
    const old = Math.floor(Date.now() / 1000) - 3600;
    expect(() => verifyStandardWebhook(payload, headers(old, `v1,${sign(old)}`), sharedSecret))
      .toThrow(RailhookError);
  });

  it('rejects a signature lifted from a different message', () => {
    const ts = Math.floor(Date.now() / 1000);
    const forAnotherMessage = crypto
      .createHmac('sha256', Buffer.from(secretB64, 'base64'))
      .update(`msg_somethingelse.${ts}.${payload}`)
      .digest('base64');

    expect(() =>
      verifyStandardWebhook(payload, headers(ts, `v1,${forAnotherMessage}`), sharedSecret)
    ).toThrow(RailhookError);
  });

  it('rejects a tampered body', () => {
    const ts = Math.floor(Date.now() / 1000);
    expect(() =>
      verifyStandardWebhook('{"test": 1}', headers(ts, `v1,${sign(ts)}`), sharedSecret)
    ).toThrow(RailhookError);
  });

  it('reports missing headers rather than treating them as unsigned', () => {
    const ts = Math.floor(Date.now() / 1000);
    expect(() => verifyStandardWebhook(payload, { 'webhook-id': id }, sharedSecret))
      .toThrow(RailhookError);
    expect(() =>
      verifyStandardWebhook(payload, { ...headers(ts, `v1,${sign(ts)}`), 'webhook-id': undefined }, sharedSecret)
    ).toThrow(RailhookError);
  });

  it('ignores signature entries of an unknown version', () => {
    const ts = Math.floor(Date.now() / 1000);
    expect(() => verifyStandardWebhook(payload, headers(ts, `v2,${sign(ts)}`), sharedSecret))
      .toThrow(RailhookError);
  });
});
