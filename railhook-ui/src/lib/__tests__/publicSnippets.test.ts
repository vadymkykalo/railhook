// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  CLI_LISTEN_EXAMPLE,
  PROVIDER_SIGNATURE_HEADERS,
  canonicalTimezone,
  formatBytes,
  sendEventCurl,
} from '../publicSnippets';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

afterEach(() => {
  delete (window as { __RAILHOOK__?: unknown }).__RAILHOOK__;
});

describe('sendEventCurl', () => {
  it('targets the API this dashboard itself calls, not the canonical site URL', () => {
    // The curl targets this origin: siteUrl may be a stale localhost:5173 nothing serves.
    (window as { __RAILHOOK__?: unknown }).__RAILHOOK__ = { siteUrl: 'http://localhost:5173' };
    const curl = sendEventCurl({ payload: '{"a":1}' });
    expect(curl).toContain(`curl -X POST ${window.location.origin}/api/v1/events`);
    expect(curl).not.toMatch(/localhost:5173|your-api\.com|your-domain\.com|example\.com/);
  });

  it('keeps the key a placeholder and the payload verbatim', () => {
    const curl = sendEventCurl({ payload: '{"type":"order.paid"}', apiKey: '$RAILHOOK_API_KEY' });
    expect(curl).toContain('X-API-Key: $RAILHOOK_API_KEY');
    expect(curl).toContain(`-d '{"type":"order.paid"}'`);
  });
});

describe('CLI_LISTEN_EXAMPLE', () => {
  it('matches the CLI: the port is positional and -p is --project', () => {
    const listen = read('railhook-cli/src/main/java/com/webhook/platform/cli/command/ListenCommand.java');
    expect(listen).toMatch(/@Parameters\(index = "0", description = "Local port/);
    expect(listen).toMatch(/names = \{"-p", "--project"\}/);
    expect(CLI_LISTEN_EXAMPLE).toMatch(/^railhook listen \d+$/);
  });

  it.each(['en', 'uk'])('the %s tunnels empty state shows exactly that command', (lang) => {
    const locale = JSON.parse(read(`railhook-ui/src/i18n/locales/${lang}.json`));
    const text: string = locale.tunnels.noTunnelsDesc;
    expect(text).toContain(CLI_LISTEN_EXAMPLE);
    expect(text).not.toMatch(/--port/);
  });
});

describe('formatBytes', () => {
  it.each([
    [0, '0 B'],
    [40, '40 B'],
    [1023, '1023 B'],
    [1024, '1.0 KB'],
    [1536, '1.5 KB'],
    [5 * 1024 * 1024, '5.0 MB'],
  ])('%d bytes → %s', (bytes, expected) => {
    expect(formatBytes(bytes)).toBe(expected);
  });
});

describe('canonicalTimezone', () => {
  it('names Kyiv by its current IANA name', () => {
    expect(canonicalTimezone('Europe/Kiev')).toBe('Europe/Kyiv');
  });

  it('leaves every other zone alone', () => {
    expect(canonicalTimezone('Europe/Berlin')).toBe('Europe/Berlin');
    expect(canonicalTimezone('UTC')).toBe('UTC');
  });
});

describe('PROVIDER_SIGNATURE_HEADERS', () => {
  const verifiers: Record<string, string> = {
    GITHUB: 'GitHubVerifier',
    STRIPE: 'StripeVerifier',
    SHOPIFY: 'ShopifyVerifier',
    SLACK: 'SlackVerifier',
    GITLAB: 'GitLabVerifier',
    TWILIO: 'TwilioVerifier',
  };

  it.each(Object.entries(verifiers))('%s matches %s', (provider, verifier) => {
    const source = read(`railhook-api/src/main/java/com/webhook/platform/api/service/verification/${verifier}.java`);
    const header = PROVIDER_SIGNATURE_HEADERS[provider as keyof typeof PROVIDER_SIGNATURE_HEADERS];
    expect(header, provider).toBeTruthy();
    expect(source).toContain(`"${header}"`);
  });

  it('has no header for a generic source, which names its own', () => {
    expect(PROVIDER_SIGNATURE_HEADERS.GENERIC).toBeUndefined();
  });
});
