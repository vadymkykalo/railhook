import { Railhook } from '../client';
import * as http from 'http';

// Minimal mock HTTP server to verify request paths & methods
let server: http.Server;
let lastRequest: { method: string; url: string; body: string; headers: http.IncomingHttpHeaders };
let mockResponse: { status: number; body: unknown } = { status: 200, body: {} };

const PORT = 19876;

beforeAll((done) => {
  server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      lastRequest = {
        method: req.method || '',
        url: req.url || '',
        body,
        headers: req.headers,
      };
      res.writeHead(mockResponse.status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(mockResponse.body));
    });
  });
  server.listen(PORT, done);
});

afterAll((done) => {
  server.close(done);
});

function setMockResponse(status: number, body: unknown) {
  mockResponse = { status, body };
}

function createClient(): Railhook {
  return new Railhook({
    apiKey: 'test_key',
    baseUrl: `http://localhost:${PORT}`,
  });
}

describe('IncomingSources', () => {
  const client = createClient();
  const projectId = 'proj-123';
  const sourceId = 'src-456';

  const sourceResponse = {
    id: sourceId,
    projectId,
    name: 'Stripe Webhooks',
    slug: 'stripe',
    providerType: 'STRIPE',
    status: 'ACTIVE',
    ingressPathToken: 'tok_abc',
    ingressUrl: `http://localhost:${PORT}/ingress/tok_abc`,
    verificationMode: 'HMAC_GENERIC',
    hmacHeaderName: 'Stripe-Signature',
    hmacSecretConfigured: true,
    createdAt: '2024-01-01T00:00:00Z',
  };

  describe('create', () => {
    it('should POST to correct path with params', async () => {
      setMockResponse(200, sourceResponse);

      const result = await client.incomingSources.create(projectId, {
        name: 'Stripe Webhooks',
        slug: 'stripe',
        providerType: 'STRIPE',
        verificationMode: 'HMAC_GENERIC',
        hmacSecret: 'whsec_test',
        hmacHeaderName: 'Stripe-Signature',
      });

      expect(lastRequest.method).toBe('POST');
      expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/incoming-sources`);
      const body = JSON.parse(lastRequest.body);
      expect(body.name).toBe('Stripe Webhooks');
      expect(body.providerType).toBe('STRIPE');
      expect(body.hmacSecret).toBe('whsec_test');
      expect(result.id).toBe(sourceId);
      expect(result.ingressUrl).toContain('tok_abc');
    });
  });

  describe('get', () => {
    it('should GET by source ID', async () => {
      setMockResponse(200, sourceResponse);

      const result = await client.incomingSources.get(projectId, sourceId);

      expect(lastRequest.method).toBe('GET');
      expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/incoming-sources/${sourceId}`);
      expect(result.name).toBe('Stripe Webhooks');
    });
  });

  describe('list', () => {
    it('should GET sources list', async () => {
      setMockResponse(200, { content: [sourceResponse], totalElements: 1, totalPages: 1, size: 20, number: 0 });

      const result = await client.incomingSources.list(projectId);

      expect(lastRequest.method).toBe('GET');
      expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/incoming-sources`);
      expect(result.content).toHaveLength(1);
      expect(result.content[0].slug).toBe('stripe');
    });
  });

  describe('update', () => {
    it('should PUT with update params', async () => {
      setMockResponse(200, { ...sourceResponse, name: 'Updated' });

      const result = await client.incomingSources.update(projectId, sourceId, {
        name: 'Updated',
        status: 'DISABLED',
      });

      expect(lastRequest.method).toBe('PUT');
      expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/incoming-sources/${sourceId}`);
      const body = JSON.parse(lastRequest.body);
      expect(body.name).toBe('Updated');
      expect(body.status).toBe('DISABLED');
      expect(result.name).toBe('Updated');
    });
  });

  describe('delete', () => {
    it('should DELETE by source ID', async () => {
      setMockResponse(204, '');

      await client.incomingSources.delete(projectId, sourceId);

      expect(lastRequest.method).toBe('DELETE');
      expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/incoming-sources/${sourceId}`);
    });
  });

  // ── Destinations ──

  const destId = 'dest-789';
  const destResponse = {
    id: destId,
    incomingSourceId: sourceId,
    url: 'https://api.example.com/webhooks/stripe',
    authType: 'NONE',
    authConfigured: false,
    enabled: true,
    maxAttempts: 5,
    timeoutSeconds: 30,
    createdAt: '2024-01-01T00:00:00Z',
  };

  describe('createDestination', () => {
    it('should POST to destinations path', async () => {
      setMockResponse(200, destResponse);

      const result = await client.incomingSources.createDestination(projectId, sourceId, {
        url: 'https://api.example.com/webhooks/stripe',
        enabled: true,
        maxAttempts: 5,
      });

      expect(lastRequest.method).toBe('POST');
      expect(lastRequest.url).toBe(
        `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations`
      );
      const body = JSON.parse(lastRequest.body);
      expect(body.url).toBe('https://api.example.com/webhooks/stripe');
      expect(result.id).toBe(destId);
    });
  });

  describe('getDestination', () => {
    it('should GET destination by ID', async () => {
      setMockResponse(200, destResponse);

      const result = await client.incomingSources.getDestination(projectId, sourceId, destId);

      expect(lastRequest.method).toBe('GET');
      expect(lastRequest.url).toBe(
        `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations/${destId}`
      );
      expect(result.url).toBe('https://api.example.com/webhooks/stripe');
    });
  });

  describe('listDestinations', () => {
    it('should GET destinations list', async () => {
      setMockResponse(200, { content: [destResponse], totalElements: 1, totalPages: 1, size: 20, number: 0 });

      const result = await client.incomingSources.listDestinations(projectId, sourceId);

      expect(lastRequest.method).toBe('GET');
      expect(lastRequest.url).toBe(
        `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations`
      );
      expect(result.content).toHaveLength(1);
    });
  });

  describe('updateDestination', () => {
    it('should PUT with update params', async () => {
      setMockResponse(200, { ...destResponse, enabled: false });

      const result = await client.incomingSources.updateDestination(projectId, sourceId, destId, {
        enabled: false,
      });

      expect(lastRequest.method).toBe('PUT');
      expect(lastRequest.url).toBe(
        `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations/${destId}`
      );
      expect(result.enabled).toBe(false);
    });
  });

  describe('deleteDestination', () => {
    it('should DELETE destination', async () => {
      setMockResponse(204, '');

      await client.incomingSources.deleteDestination(projectId, sourceId, destId);

      expect(lastRequest.method).toBe('DELETE');
      expect(lastRequest.url).toBe(
        `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations/${destId}`
      );
    });
  });
});

describe('IncomingEvents', () => {
  const client = createClient();
  const projectId = 'proj-123';
  const eventId = 'evt-abc';

  const eventResponse = {
    id: eventId,
    incomingSourceId: 'src-456',
    sourceName: 'Stripe Webhooks',
    requestId: 'req-xyz',
    method: 'POST',
    path: '/ingress/tok_abc',
    bodyRaw: '{"type":"checkout.session.completed"}',
    contentType: 'application/json',
    verified: true,
    receivedAt: '2024-01-01T00:00:00Z',
  };

  describe('list', () => {
    it('should GET events without params', async () => {
      setMockResponse(200, { content: [eventResponse], totalElements: 1, totalPages: 1, size: 20, number: 0 });

      const result = await client.incomingEvents.list(projectId);

      expect(lastRequest.method).toBe('GET');
      expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/incoming-events`);
      expect(result.content).toHaveLength(1);
      expect(result.content[0].requestId).toBe('req-xyz');
    });

    it('should pass sourceId filter as query param', async () => {
      setMockResponse(200, { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 });

      await client.incomingEvents.list(projectId, { sourceId: 'src-456' });

      expect(lastRequest.url).toContain('sourceId=src-456');
    });

    it('should pass pagination params', async () => {
      setMockResponse(200, { content: [], totalElements: 0, totalPages: 0, size: 10, number: 2 });

      await client.incomingEvents.list(projectId, { page: 2, size: 10 });

      expect(lastRequest.url).toContain('page=2');
      expect(lastRequest.url).toContain('size=10');
    });
  });

  describe('get', () => {
    it('should GET event by ID', async () => {
      setMockResponse(200, eventResponse);

      const result = await client.incomingEvents.get(projectId, eventId);

      expect(lastRequest.method).toBe('GET');
      expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/incoming-events/${eventId}`);
      expect(result.verified).toBe(true);
      expect(result.method).toBe('POST');
    });
  });

  describe('getAttempts', () => {
    it('should GET forward attempts', async () => {
      const attemptResponse = {
        content: [{
          id: 'att-1',
          incomingEventId: eventId,
          destinationId: 'dest-789',
          destinationUrl: 'https://api.example.com/webhooks/stripe',
          attemptNumber: 1,
          status: 'SUCCESS',
          startedAt: '2024-01-01T00:00:00Z',
          finishedAt: '2024-01-01T00:00:01Z',
          responseCode: 200,
          responseBodySnippet: 'OK',
          createdAt: '2024-01-01T00:00:00Z',
        }],
        totalElements: 1,
        totalPages: 1,
        size: 20,
        number: 0,
      };
      setMockResponse(200, attemptResponse);

      const result = await client.incomingEvents.getAttempts(projectId, eventId);

      expect(lastRequest.method).toBe('GET');
      expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/incoming-events/${eventId}/attempts`);
      expect(result.content).toHaveLength(1);
      expect(result.content[0].responseCode).toBe(200);
      expect(result.content[0].status).toBe('SUCCESS');
      expect(result.content[0].attemptNumber).toBe(1);
      expect(result.content[0].destinationUrl).toBe('https://api.example.com/webhooks/stripe');
    });
  });

  describe('replay', () => {
    it('should POST replay', async () => {
      setMockResponse(200, { status: 'replayed', eventId, destinationsCount: 2 });

      const result = await client.incomingEvents.replay(projectId, eventId);

      expect(lastRequest.method).toBe('POST');
      expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/incoming-events/${eventId}/replay`);
      expect(result.status).toBe('replayed');
      expect(result.destinationsCount).toBe(2);
    });
  });
});

describe('Client initialization includes incoming modules', () => {
  it('should have incomingSources and incomingEvents', () => {
    const client = new Railhook({ apiKey: 'test_key' });
    expect(client.incomingSources).toBeDefined();
    expect(client.incomingEvents).toBeDefined();
  });
});
