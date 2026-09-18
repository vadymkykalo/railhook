import { ConfigError, loadConfig } from '../config';
import { main } from '../cli';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const pkg = require('../../package.json');

describe('configuration', () => {
  it('points at Railhook Cloud by default', () => {
    const config = loadConfig({ RAILHOOK_API_KEY: 'k' });
    expect(config.url.toString()).toBe('https://railhook.io/mcp');
    expect(config.apiKey).toBe('k');
  });

  it('puts /mcp under a self-hosted origin, with or without a trailing slash', () => {
    expect(loadConfig({ RAILHOOK_API_KEY: 'k', RAILHOOK_BASE_URL: 'https://hooks.example.com/' }).url.toString())
      .toBe('https://hooks.example.com/mcp');
    expect(loadConfig({ RAILHOOK_API_KEY: 'k', RAILHOOK_BASE_URL: 'http://localhost:8080' }).url.toString())
      .toBe('http://localhost:8080/mcp');
  });

  it('refuses to start without an API key, and says how to give it one', () => {
    expect(() => loadConfig({})).toThrow(ConfigError);
    expect(() => loadConfig({ RAILHOOK_API_KEY: '  ' })).toThrow(/RAILHOOK_API_KEY is not set/);
  });

  it('refuses a base URL that is not http(s)', () => {
    expect(() => loadConfig({ RAILHOOK_API_KEY: 'k', RAILHOOK_BASE_URL: 'not a url' })).toThrow(/RAILHOOK_BASE_URL/);
    expect(() => loadConfig({ RAILHOOK_API_KEY: 'k', RAILHOOK_BASE_URL: 'ftp://example.com' })).toThrow(/http\(s\)/);
  });
});

describe('the railhook-mcp command', () => {
  it('exits 1 with the reason on stderr when RAILHOOK_API_KEY is missing', async () => {
    const written: string[] = [];
    const code = await main({}, { write: (chunk: string) => written.push(chunk) > 0 } as never);

    expect(code).toBe(1);
    expect(written.join('')).toMatch(/^railhook-mcp: RAILHOOK_API_KEY is not set/);
  });
});

describe('package identity (@railhook/mcp)', () => {
  it('is published as @railhook/mcp with a railhook-mcp binary', () => {
    expect(pkg.name).toBe('@railhook/mcp');
    expect(pkg.bin['railhook-mcp']).toBe('dist/cli.js');
  });
});
