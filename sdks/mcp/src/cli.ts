#!/usr/bin/env node
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { createBridge } from './bridge';
import { ConfigError, loadConfig } from './config';

/**
 * Starts the stdio bridge. Returns the exit code for a failure it can explain, and nothing while
 * the server runs — stdout belongs to the MCP protocol, so every diagnostic goes to stderr.
 */
export async function main(
  env: NodeJS.ProcessEnv = process.env,
  stderr: Pick<NodeJS.WriteStream, 'write'> = process.stderr
): Promise<number | undefined> {
  let config;
  try {
    config = loadConfig(env);
  } catch (error) {
    if (error instanceof ConfigError) {
      stderr.write(`railhook-mcp: ${error.message}\n`);
      return 1;
    }
    throw error;
  }

  const server = createBridge(config);
  await server.connect(new StdioServerTransport());
  stderr.write(`railhook-mcp: bridging stdio to ${config.url.toString()}\n`);
  return undefined;
}

if (require.main === module) {
  main().then(
    (code) => {
      if (code !== undefined) process.exit(code);
    },
    (error) => {
      process.stderr.write(`railhook-mcp: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exit(1);
    }
  );
}
