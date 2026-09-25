import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import {
  StreamableHTTPClientTransport,
  StreamableHTTPError,
} from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import {
  CallToolRequestSchema,
  CallToolResult,
  ErrorCode,
  ListToolsRequestSchema,
  McpError,
} from '@modelcontextprotocol/sdk/types.js';
import { BridgeConfig } from './config';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const pkg: { version: string } = require('../package.json');

export const VERSION = pkg.version;

export interface BridgeOptions extends BridgeConfig {
  /** Replaces the global fetch; tests use it to observe or fail the HTTP calls. */
  fetch?: typeof fetch;
}

/**
 * Relays `tools/list` and `tools/call` unchanged to `<RAILHOOK_BASE_URL>/mcp`. Connects on the first
 * request, so an offline start or a bad key surfaces as an error on the call.
 */
export function createBridge(options: BridgeOptions): Server {
  const server = new Server(
    { name: 'railhook', version: VERSION },
    {
      capabilities: { tools: {} },
      instructions:
        "Railhook delivers a customer's events to the endpoints subscribed to them. The API key this " +
        'server was given belongs to one project; every tool acts on that project only.',
    }
  );

  let remote: Promise<Client> | undefined;

  const connect = (): Promise<Client> => {
    if (!remote) {
      remote = openRemote(options).catch((error) => {
        remote = undefined;
        throw error;
      });
    }
    return remote;
  };

  server.setRequestHandler(ListToolsRequestSchema, async (request) => {
    try {
      const client = await connect();
      return await client.listTools(request.params);
    } catch (error) {
      throw new McpError(ErrorCode.InternalError, describeRemoteError(error, options));
    }
  });

  server.setRequestHandler(CallToolRequestSchema, async (request): Promise<CallToolResult> => {
    try {
      const client = await connect();
      return (await client.callTool(request.params)) as CallToolResult;
    } catch (error) {
      return { isError: true, content: [{ type: 'text', text: describeRemoteError(error, options) }] };
    }
  });

  server.onclose = () => {
    void remote?.then((client) => client.close()).catch(() => undefined);
  };

  return server;
}

async function openRemote(options: BridgeOptions): Promise<Client> {
  const transport = new StreamableHTTPClientTransport(options.url, {
    requestInit: {
      headers: {
        Authorization: `Bearer ${options.apiKey}`,
        'User-Agent': `railhook-mcp/${VERSION}`,
      },
    },
    fetch: options.fetch,
  });
  const client = new Client({ name: 'railhook-mcp', version: VERSION });
  await client.connect(transport);
  return client;
}

/** Turns a remote failure into an actionable sentence, with the API key redacted from any echo. */
export function describeRemoteError(error: unknown, options: Pick<BridgeConfig, 'apiKey' | 'url'>): string {
  const where = options.url.toString();
  let message: string;

  if (error instanceof StreamableHTTPError) {
    switch (error.code) {
      case 401:
        message =
          `Railhook rejected the API key (HTTP 401) at ${where}. Check RAILHOOK_API_KEY: it must be a ` +
          'live, unrevoked project API key from the instance RAILHOOK_BASE_URL points at.';
        break;
      case 403:
        message = `Railhook refused the request (HTTP 403) at ${where}. ${remoteText(error)}`.trim();
        break;
      case 404:
        message =
          `No MCP server answered at ${where} (HTTP 404). Check RAILHOOK_BASE_URL; a self-hosted ` +
          'instance serves it only while MCP_ENABLED is true.';
        break;
      default:
        message = `Railhook answered HTTP ${error.code} at ${where}. ${remoteText(error)}`.trim();
    }
  } else if (error instanceof McpError) {
    message = error.message;
  } else if (error instanceof Error) {
    const cause = (error as Error & { cause?: unknown }).cause;
    const detail = cause instanceof Error ? `${error.message}: ${cause.message}` : error.message;
    message = error.name === 'TypeError' ? `Could not reach ${where}: ${detail}` : detail;
  } else {
    message = String(error);
  }

  return redact(message, options.apiKey);
}

function remoteText(error: StreamableHTTPError): string {
  return error.message.replace(/^Streamable HTTP error:\s*/, '').replace(/^Error POSTing to endpoint:\s*/, '');
}

function redact(text: string, secret: string): string {
  return secret ? text.split(secret).join('[redacted]') : text;
}
