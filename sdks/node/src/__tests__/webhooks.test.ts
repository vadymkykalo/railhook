import { verifySignature, constructEvent, generateSignature } from '../webhooks';
import { RailhookError } from '../errors';

describe('Webhook Signature Verification', () => {
  const secret = 'whsec_test_secret_key_123';
  const payload = JSON.stringify({ type: 'order.completed', data: { orderId: '12345' } });

  describe('generateSignature', () => {
    it('should generate valid signature format', () => {
      const signature = generateSignature(payload, secret);
      expect(signature).toMatch(/^t=\d+,v1=[a-f0-9]{64}$/);
    });

    it('should use provided timestamp', () => {
      const timestamp = 1700000000000;
      const signature = generateSignature(payload, secret, timestamp);
      expect(signature).toContain(`t=${timestamp}`);
    });

    it('should generate consistent signatures for same inputs', () => {
      const timestamp = 1700000000000;
      const sig1 = generateSignature(payload, secret, timestamp);
      const sig2 = generateSignature(payload, secret, timestamp);
      expect(sig1).toBe(sig2);
    });

    it('should generate different signatures for different payloads', () => {
      const timestamp = 1700000000000;
      const sig1 = generateSignature(payload, secret, timestamp);
      const sig2 = generateSignature('different payload', secret, timestamp);
      expect(sig1).not.toBe(sig2);
    });

    it('should generate different signatures for different secrets', () => {
      const timestamp = 1700000000000;
      const sig1 = generateSignature(payload, secret, timestamp);
      const sig2 = generateSignature(payload, 'different_secret', timestamp);
      expect(sig1).not.toBe(sig2);
    });
  });

  /**
   * After a rotation Railhook signs each delivery with the new secret and the retired one
   * for the endpoint's grace window, so a receiver that has not deployed the new secret yet
   * keeps working. The parser used to keep only the last v1 and rejected whichever half of
   * the pair the receiver was holding.
   */
  describe('a header carrying two signatures (secret rotation)', () => {
    const newSecret = 'whsec_new';
    const retiredSecret = 'whsec_retired';

    const dualHeader = (body: string, at = Date.now()) => {
      const retiredV1 = generateSignature(body, retiredSecret, at).split('v1=')[1];
      return `${generateSignature(body, newSecret, at)},v1=${retiredV1}`;
    };

    it('verifies with the new secret', () => {
      expect(verifySignature(payload, dualHeader(payload), newSecret)).toBe(true);
    });

    it('verifies with the retired secret', () => {
      expect(verifySignature(payload, dualHeader(payload), retiredSecret)).toBe(true);
    });

    it('still rejects an unrelated secret', () => {
      expect(() => verifySignature(payload, dualHeader(payload), 'whsec_someone_else'))
        .toThrow(RailhookError);
    });

    it('still rejects a tampered body', () => {
      expect(() => verifySignature(`${payload} `, dualHeader(payload), newSecret))
        .toThrow(RailhookError);
    });
  });

  describe('verifySignature', () => {
    it('should verify valid signature', () => {
      const timestamp = Date.now();
      const signature = generateSignature(payload, secret, timestamp);
      expect(verifySignature(payload, signature, secret)).toBe(true);
    });

    it('should throw on missing signature', () => {
      expect(() => verifySignature(payload, '', secret)).toThrow(RailhookError);
      expect(() => verifySignature(payload, '', secret)).toThrow('Missing signature header');
    });

    it('should throw on invalid signature format', () => {
      expect(() => verifySignature(payload, 'invalid', secret)).toThrow('Invalid signature format');
    });

    it('should throw on missing timestamp', () => {
      expect(() => verifySignature(payload, 'v1=abc123', secret)).toThrow('Invalid signature format');
    });

    it('should throw on missing v1 signature', () => {
      expect(() => verifySignature(payload, 't=1700000000000', secret)).toThrow('Invalid signature format');
    });

    it('should throw on expired timestamp', () => {
      const oldTimestamp = Date.now() - 600000; // 10 minutes ago
      const signature = generateSignature(payload, secret, oldTimestamp);
      expect(() => verifySignature(payload, signature, secret)).toThrow('outside tolerance window');
    });

    it('should throw on future timestamp outside tolerance', () => {
      const futureTimestamp = Date.now() + 600000; // 10 minutes in future
      const signature = generateSignature(payload, secret, futureTimestamp);
      expect(() => verifySignature(payload, signature, secret)).toThrow('outside tolerance window');
    });

    it('should accept timestamp within tolerance', () => {
      const recentTimestamp = Date.now() - 60000; // 1 minute ago
      const signature = generateSignature(payload, secret, recentTimestamp);
      expect(verifySignature(payload, signature, secret)).toBe(true);
    });

    it('should throw on invalid signature value', () => {
      const timestamp = Date.now();
      const signature = `t=${timestamp},v1=invalid_signature_that_will_not_match`;
      expect(() => verifySignature(payload, signature, secret)).toThrow('Invalid signature');
    });

    it('should throw on tampered payload', () => {
      const timestamp = Date.now();
      const signature = generateSignature(payload, secret, timestamp);
      const tamperedPayload = JSON.stringify({ type: 'order.cancelled', data: {} });
      expect(() => verifySignature(tamperedPayload, signature, secret)).toThrow('Invalid signature');
    });

    it('should respect custom tolerance', () => {
      const oldTimestamp = Date.now() - 60000; // 1 minute ago
      const signature = generateSignature(payload, secret, oldTimestamp);
      
      // Should fail with 30s tolerance
      expect(() => verifySignature(payload, signature, secret, { tolerance: 30000 }))
        .toThrow('outside tolerance window');
      
      // Should pass with 2min tolerance
      expect(verifySignature(payload, signature, secret, { tolerance: 120000 })).toBe(true);
    });
  });

  describe('constructEvent', () => {
    it('should construct event from valid request', () => {
      const timestamp = Date.now();
      const signature = generateSignature(payload, secret, timestamp);
      
      const headers = {
        'x-signature': signature,
        'x-timestamp': timestamp.toString(),
        'x-event-id': 'evt_123',
        'x-delivery-id': 'dlv_456',
      };

      const event = constructEvent(payload, headers, secret);
      
      expect(event.eventId).toBe('evt_123');
      expect(event.deliveryId).toBe('dlv_456');
      expect(event.timestamp).toBe(timestamp);
      expect(event.type).toBe('order.completed');
      expect(event.data).toEqual({ orderId: '12345' });
    });

    it('should handle uppercase headers', () => {
      const timestamp = Date.now();
      const signature = generateSignature(payload, secret, timestamp);
      
      const headers = {
        'X-Signature': signature,
        'X-Timestamp': timestamp.toString(),
        'X-Event-Id': 'evt_123',
        'X-Delivery-Id': 'dlv_456',
      };

      const event = constructEvent(payload, headers, secret);
      expect(event.eventId).toBe('evt_123');
    });

    it('should throw on missing signature header', () => {
      const headers = {
        'x-timestamp': Date.now().toString(),
      };

      expect(() => constructEvent(payload, headers, secret))
        .toThrow('Missing X-Signature header');
    });

    it('should throw on invalid JSON payload', () => {
      const timestamp = Date.now();
      const invalidPayload = 'not valid json';
      const signature = generateSignature(invalidPayload, secret, timestamp);
      
      const headers = {
        'x-signature': signature,
        'x-timestamp': timestamp.toString(),
      };

      expect(() => constructEvent(invalidPayload, headers, secret))
        .toThrow('Invalid JSON payload');
    });

    it('should handle payload without nested data field', () => {
      const flatPayload = JSON.stringify({ type: 'test.event', value: 123 });
      const timestamp = Date.now();
      const signature = generateSignature(flatPayload, secret, timestamp);
      
      const headers = {
        'x-signature': signature,
        'x-timestamp': timestamp.toString(),
      };

      const event = constructEvent(flatPayload, headers, secret);
      expect(event.type).toBe('test.event');
      expect(event.data).toEqual({ type: 'test.event', value: 123 });
    });

    it('should use current timestamp if not provided in headers', () => {
      const timestamp = Date.now();
      const signature = generateSignature(payload, secret, timestamp);
      
      const headers = {
        'x-signature': signature,
      };

      const event = constructEvent(payload, headers, secret);
      expect(event.timestamp).toBeGreaterThan(0);
    });
  });
});
