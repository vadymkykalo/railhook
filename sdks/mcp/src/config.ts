export const DEFAULT_BASE_URL = 'https://railhook.io';

export interface BridgeConfig {
  /** The project API key, sent to the remote server as a bearer token. */
  apiKey: string;
  /** The remote MCP server: `<RAILHOOK_BASE_URL>/mcp`. */
  url: URL;
}

/** A configuration problem the user has to fix; its message is written for them. */
export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigError';
  }
}

/**
 * Reads the bridge's configuration from the environment.
 *
 * `RAILHOOK_API_KEY` is required. `RAILHOOK_BASE_URL` is the origin the dashboard is served on —
 * `https://railhook.io` for Railhook Cloud, your own origin when self-hosted — and the remote
 * server is always at `/mcp` under it.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
  const apiKey = env.RAILHOOK_API_KEY?.trim();
  if (!apiKey) {
    throw new ConfigError(
      'RAILHOOK_API_KEY is not set. Create a project API key in the Railhook dashboard ' +
        '(a READ_ONLY key is enough to explore) and pass it in the environment, e.g. the "env" ' +
        'block of your MCP client\'s config for this server.'
    );
  }

  const base = (env.RAILHOOK_BASE_URL?.trim() || DEFAULT_BASE_URL).replace(/\/+$/, '');
  let url: URL;
  try {
    url = new URL(`${base}/mcp`);
  } catch {
    throw new ConfigError(`RAILHOOK_BASE_URL is not a URL: '${base}'. Expected e.g. https://railhook.io`);
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    throw new ConfigError(`RAILHOOK_BASE_URL must be an http(s) URL, got '${base}'`);
  }

  return { apiKey, url };
}
