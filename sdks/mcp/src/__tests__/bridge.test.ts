import * as http from 'http';
import { AddressInfo } from 'net';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createBridge } from '../bridge';

const API_KEY = 'rk_test_0123456789abcdef';

const REMOTE_TOOLS = [
  {
    name: 'list_endpoints',
    description: "Lists the project's Endpoints.",
    inputSchema: { type: 'object', properties: { page: { type: 'integer' } } },
    annotations: { readOnlyHint: true, destructiveHint: false },
  },
  {
    name: 'send_event',
    description: 'Sends an Event into the project.',
    inputSchema: { type: 'object', properties: { type: { type: 'string' }, data: { type: 'object' } } },
    annotations: { readOnlyHint: false, destructiveHint: false },
  },
];

interface FakeRemote {
  url: URL;
  /** Every JSON-RPC request the remote received, with the Authorization header it came with. */
  calls: { method: string; params: any; authorization?: string }[];
  /** When set, every POST is answered with this status and body instead. */
  failWith?: { status: number; body: string };
  close(): Promise<void>;
}

/**
 * A stand-in for Railhook's /mcp: stateless Streamable HTTP, one JSON answer per POST, GET
 * refused with 405 — what the Spring AI server in railhook-api does.
 */
async function startFakeRemote(): Promise<FakeRemote> {
  const remote: FakeRemote = { url: new URL('http://127.0.0.1'), calls: [], close: async () => undefined };

  const server = http.createServer((req, res) => {
    if (req.method !== 'POST') {
      res.writeHead(405).end();
      return;
    }
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const message = JSON.parse(body);
      remote.calls.push({ method: message.method, params: message.params, authorization: req.headers.authorization });

      if (remote.failWith) {
        res.writeHead(remote.failWith.status, { 'content-type': 'application/json' }).end(remote.failWith.body);
        return;
      }
      if (req.headers.authorization !== `Bearer ${API_KEY}`) {
        res.writeHead(401, { 'content-type': 'application/json' })
          .end('{"error":"unauthorized","message":"Authentication required","status":401}');
        return;
      }
      if (message.id === undefined) {
        res.writeHead(202).end();
        return;
      }

      let result: unknown;
      switch (message.method) {
        case 'initialize':
          result = {
            protocolVersion: message.params.protocolVersion,
            capabilities: { tools: {} },
            serverInfo: { name: 'railhook', version: '1.0.0' },
          };
          break;
        case 'tools/list':
          result = { tools: REMOTE_TOOLS };
          break;
        case 'tools/call':
          result = {
            content: [{ type: 'text', text: JSON.stringify({ called: message.params.name, with: message.params.arguments }) }],
            isError: false,
          };
          break;
        default:
          res.writeHead(200, { 'content-type': 'application/json' }).end(
            JSON.stringify({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Method not found' } })
          );
          return;
      }
      res.writeHead(200, { 'content-type': 'application/json' })
        .end(JSON.stringify({ jsonrpc: '2.0', id: message.id, result }));
    });
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  remote.url = new URL(`http://127.0.0.1:${port}/mcp`);
  remote.close = () => new Promise((resolve) => server.close(() => resolve()));
  return remote;
}

/** An MCP client talking to the bridge the way Claude Desktop would over stdio. */
async function connectToBridge(remoteUrl: URL, apiKey = API_KEY): Promise<Client> {
  const bridge = createBridge({ apiKey, url: remoteUrl });
  const [clientSide, bridgeSide] = InMemoryTransport.createLinkedPair();
  await bridge.connect(bridgeSide);
  const client = new Client({ name: 'test-client', version: '0.0.0' });
  await client.connect(clientSide);
  return client;
}

describe('the stdio bridge', () => {
  let remote: FakeRemote;
  let client: Client;

  beforeEach(async () => {
    remote = await startFakeRemote();
  });

  afterEach(async () => {
    await client?.close();
    await remote.close();
  });

  it('lists exactly the tools the remote server offers, annotations included', async () => {
    client = await connectToBridge(remote.url);

    const { tools } = await client.listTools();

    expect(tools.map((t) => t.name)).toEqual(['list_endpoints', 'send_event']);
    expect(tools[0].annotations).toEqual({ readOnlyHint: true, destructiveHint: false });
    expect(tools[1].description).toBe('Sends an Event into the project.');
  });

  it('passes a tool call through with its arguments and returns the remote result', async () => {
    client = await connectToBridge(remote.url);

    const result = await client.callTool({ name: 'send_event', arguments: { type: 'order.created', data: { id: 7 } } });

    expect(result.isError).toBe(false);
    expect(JSON.parse((result.content as { text: string }[])[0].text)).toEqual({
      called: 'send_event',
      with: { type: 'order.created', data: { id: 7 } },
    });
    const call = remote.calls.find((c) => c.method === 'tools/call');
    expect(call?.params).toEqual({ name: 'send_event', arguments: { type: 'order.created', data: { id: 7 } } });
  });

  it('authenticates every request to the remote with the API key as a bearer token', async () => {
    client = await connectToBridge(remote.url);
    await client.listTools();
    await client.callTool({ name: 'list_endpoints', arguments: {} });

    expect(remote.calls.length).toBeGreaterThanOrEqual(3);
    for (const call of remote.calls) {
      expect(call.authorization).toBe(`Bearer ${API_KEY}`);
    }
  });

  it('reports a rejected key as a tool error that names the fix and not the key', async () => {
    client = await connectToBridge(remote.url, 'rk_wrong_key_value');

    const result = await client.callTool({ name: 'list_endpoints', arguments: {} });

    expect(result.isError).toBe(true);
    const text = (result.content as { text: string }[])[0].text;
    expect(text).toContain('HTTP 401');
    expect(text).toContain('RAILHOOK_API_KEY');
    expect(text).not.toContain('rk_wrong_key_value');
  });

  it('fails tools/list with a clear protocol error when the key is rejected', async () => {
    client = await connectToBridge(remote.url, 'rk_wrong_key_value');

    await expect(client.listTools()).rejects.toThrow(/HTTP 401.*RAILHOOK_API_KEY/);
  });

  it('reports a 403 with what the remote said, without the key', async () => {
    client = await connectToBridge(remote.url);
    await client.listTools();
    remote.failWith = {
      status: 403,
      body: `{"error":"forbidden","message":"This organization is suspended. key=${API_KEY}"}`,
    };

    const result = await client.callTool({ name: 'send_event', arguments: {} });

    expect(result.isError).toBe(true);
    const text = (result.content as { text: string }[])[0].text;
    expect(text).toContain('HTTP 403');
    expect(text).toContain('This organization is suspended');
    expect(text).not.toContain(API_KEY);
  });

  it('says where it looked when nothing answers at /mcp', async () => {
    remote.failWith = { status: 404, body: '' };
    client = await connectToBridge(remote.url);

    const result = await client.callTool({ name: 'list_endpoints', arguments: {} });

    expect(result.isError).toBe(true);
    const text = (result.content as { text: string }[])[0].text;
    expect(text).toContain('HTTP 404');
    expect(text).toContain(remote.url.toString());
    expect(text).toContain('RAILHOOK_BASE_URL');
  });

  it('reconnects on the next call once the remote is reachable again', async () => {
    remote.failWith = { status: 503, body: 'starting' };
    client = await connectToBridge(remote.url);
    expect((await client.callTool({ name: 'list_endpoints', arguments: {} })).isError).toBe(true);

    remote.failWith = undefined;
    const result = await client.callTool({ name: 'list_endpoints', arguments: {} });

    expect(result.isError).toBe(false);
  });
});
