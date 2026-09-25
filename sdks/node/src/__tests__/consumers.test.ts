import { Railhook } from '../client';
import * as http from 'http';

let server: http.Server;
let lastRequest: { method: string; url: string; body: string; headers: http.IncomingHttpHeaders };
let mockResponse: { status: number; body: unknown } = { status: 200, body: {} };

const PORT = 19877;

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
      if (mockResponse.status === 204) {
        res.writeHead(204);
        res.end();
        return;
      }
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

describe('Consumers', () => {
  const client = createClient();
  const projectId = 'proj-123';
  const consumerId = 'con-456';

  const consumerResponse = {
    id: consumerId,
    projectId,
    externalId: 'user-42',
    name: 'Acme Ltd',
    endpointCount: 0,
    createdAt: '2024-01-01T00:00:00Z',
  };

  it('create POSTs the external id and name', async () => {
    setMockResponse(201, consumerResponse);

    const result = await client.consumers.create(projectId, { externalId: 'user-42', name: 'Acme Ltd' });

    expect(lastRequest.method).toBe('POST');
    expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/consumers`);
    expect(JSON.parse(lastRequest.body)).toEqual({ externalId: 'user-42', name: 'Acme Ltd' });
    expect(lastRequest.headers['x-api-key']).toBe('test_key');
    expect(result.externalId).toBe('user-42');
  });

  it('list passes externalId and paging as query parameters', async () => {
    setMockResponse(200, { content: [consumerResponse], totalElements: 1, totalPages: 1 });

    const result = await client.consumers.list(projectId, { externalId: 'user-42', page: 0, size: 10 });

    expect(lastRequest.method).toBe('GET');
    expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/consumers?externalId=user-42&page=0&size=10`);
    expect(result.content[0].id).toBe(consumerId);
  });

  it('list without params has no query string', async () => {
    setMockResponse(200, { content: [], totalElements: 0, totalPages: 0 });

    await client.consumers.list(projectId);

    expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/consumers`);
  });

  it('get, update, delete and listEndpoints address the consumer', async () => {
    setMockResponse(200, consumerResponse);
    await client.consumers.get(projectId, consumerId);
    expect(lastRequest.method).toBe('GET');
    expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/consumers/${consumerId}`);

    await client.consumers.update(projectId, consumerId, { externalId: 'user-42', name: 'Acme Inc' });
    expect(lastRequest.method).toBe('PUT');
    expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/consumers/${consumerId}`);
    expect(JSON.parse(lastRequest.body).name).toBe('Acme Inc');

    setMockResponse(200, []);
    await client.consumers.listEndpoints(projectId, consumerId);
    expect(lastRequest.method).toBe('GET');
    expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/consumers/${consumerId}/endpoints`);

    setMockResponse(204, null);
    await client.consumers.delete(projectId, consumerId);
    expect(lastRequest.method).toBe('DELETE');
    expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/consumers/${consumerId}`);
  });

  it('endpoints.create forwards a consumerId', async () => {
    setMockResponse(201, { id: 'ep-1', projectId, consumerId, url: 'https://example.com/hook', enabled: true });

    const endpoint = await client.endpoints.create(projectId, { url: 'https://example.com/hook', consumerId });

    expect(JSON.parse(lastRequest.body).consumerId).toBe(consumerId);
    expect(endpoint.consumerId).toBe(consumerId);
  });
});

describe('PortalSessions', () => {
  const client = createClient();
  const projectId = 'proj-123';
  const consumerId = 'con-456';

  it('create POSTs the ttl and allowed origin and returns the portal url', async () => {
    setMockResponse(201, {
      id: 'ps-1',
      consumerId,
      url: 'https://railhook.example.com/portal?origin=https://app.example.com#rhp_abc',
      token: 'rhp_abc',
      allowedOrigin: 'https://app.example.com',
      expiresAt: '2024-01-01T01:00:00Z',
    });

    const session = await client.portalSessions.create(projectId, consumerId, {
      ttlMinutes: 30,
      allowedOrigin: 'https://app.example.com',
    });

    expect(lastRequest.method).toBe('POST');
    expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/consumers/${consumerId}/portal-sessions`);
    expect(JSON.parse(lastRequest.body)).toEqual({ ttlMinutes: 30, allowedOrigin: 'https://app.example.com' });
    expect(session.token).toBe('rhp_abc');
    expect(session.url).toContain('/portal');
  });

  it('create without params sends an empty body', async () => {
    setMockResponse(201, { id: 'ps-2', consumerId, url: 'x', token: 'rhp_x', expiresAt: 'y' });

    await client.portalSessions.create(projectId, consumerId);

    expect(JSON.parse(lastRequest.body)).toEqual({});
  });

  it('revoke DELETEs the consumer\'s sessions', async () => {
    setMockResponse(204, null);

    await client.portalSessions.revoke(projectId, consumerId);

    expect(lastRequest.method).toBe('DELETE');
    expect(lastRequest.url).toBe(`/api/v1/projects/${projectId}/consumers/${consumerId}/portal-sessions`);
  });
});
