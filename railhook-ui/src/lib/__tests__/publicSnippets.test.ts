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

/**
 * Text a person copies out of the product and runs somewhere else. QA on a fresh cloud account
 * found `https://your-api.com` and `https://your-domain.com` in two curl snippets, and a CLI
 * command (`railhook listen --port 3000`) the CLI does not accept.
 */
describe('sendEventCurl', () => {
  it('targets the deployment it is shown on, from the runtime site URL', () => {
    (window as { __RAILHOOK__?: unknown }).__RAILHOOK__ = { siteUrl: 'https://railhook.io' };
    const curl = sendEventCurl({ payload: '{"a":1}' });
    expect(curl).toContain('https://railhook.io/api/v1/events');
    expect(curl).not.toMatch(/your-api\.com|your-domain\.com|example\.com/);
  });

  it('falls back to the page origin on a deployment that declares none', () => {
    const curl = sendEventCurl({ payload: '{}' });
    expect(curl).toContain(`${window.location.origin}/api/v1/events`);
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

/**
 * The header each built-in provider signs in has to be the one the API's verifier reads. The
 * source page used to print the generic default `X-Signature` for a Stripe source, whose
 * verifier reads `Stripe-Signature`.
 */
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
