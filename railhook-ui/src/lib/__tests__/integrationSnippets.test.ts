// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import {
  cliTunnelSnippets,
  consumerPortalSnippets,
  ingressCurlSnippet,
  portalIframeSnippet,
  sendEventSnippets,
  verifySignatureSnippets,
} from '../integrationSnippets';

const BASE = 'https://hooks.example.test';
const ALL = ['curl', 'node', 'python', 'php'] as const;

/** Every sample a person copies authenticates through the env var, never with a key pasted in. */
function expectKeyPlaceholder(samples: Record<string, string | undefined>) {
  expect(samples.curl).toContain('X-API-Key: $RAILHOOK_API_KEY');
  expect(samples.node).toContain('process.env.RAILHOOK_API_KEY');
  expect(samples.python).toContain('os.environ["RAILHOOK_API_KEY"]');
  expect(samples.php).toContain("getenv('RAILHOOK_API_KEY')");
}

describe('sendEventSnippets', () => {
  const samples = sendEventSnippets({ baseUrl: BASE });

  it('covers every language and points each one at this deployment', () => {
    for (const lang of ALL) expect(samples[lang]).toContain(BASE);
    expect(samples.curl).toContain(`${BASE}/api/v1/events`);
    expectKeyPlaceholder(samples);
  });

  it('calls the SDK methods that exist', () => {
    expect(samples.node).toContain('client.events.send({');
    expect(samples.python).toContain('client.events.send(Event(');
    expect(samples.php).toContain('$client->events->send(');
  });
});

describe('consumerPortalSnippets', () => {
  it('creates a Consumer and opens a session in the project the page is showing', () => {
    const samples = consumerPortalSnippets({ baseUrl: BASE, projectId: 'proj-42' });
    for (const lang of ALL) expect(samples[lang]).toContain('proj-42');
    expect(samples.node).toContain('railhook.consumers.create(');
    expect(samples.node).toContain('railhook.portalSessions.create(');
    expect(samples.python).toContain('client.consumers.create(');
    expect(samples.python).toContain('client.portal_sessions.create(');
    expect(samples.python).toContain('ConsumerCreateParams(external_id=');
    expect(samples.php).toContain('$client->consumers->create(');
    expect(samples.php).toContain('$client->portalSessions->create(');
    expect(samples.curl).toContain(`${BASE}/api/v1/projects/proj-42/consumers`);
    expectKeyPlaceholder(samples);
  });

  it('skips creating the Consumer when it already exists, and names it by id', () => {
    const samples = consumerPortalSnippets({ baseUrl: BASE, projectId: 'proj-42', consumerId: 'cons-7' });
    for (const lang of ALL) {
      expect(samples[lang]).toContain('cons-7');
      expect(samples[lang]).not.toMatch(/consumers\.create|consumers->create|"externalId"/);
    }
  });
});

describe('verifySignatureSnippets', () => {
  it('verifies X-Signature by default, with the endpoint secret from the environment', () => {
    const samples = verifySignatureSnippets({ scheme: 'BOTH' });
    expect(samples.curl).toBeUndefined();
    expect(samples.node).toContain('constructEvent(');
    expect(samples.python).toContain('construct_event(');
    expect(samples.php).toContain('Webhook::constructEvent(');
    expect(samples.node).toContain('process.env.WEBHOOK_SECRET');
  });

  it('verifies the Standard Webhooks headers when the endpoint sends only those', () => {
    const samples = verifySignatureSnippets({ scheme: 'STANDARD' });
    expect(samples.node).toContain('verifyStandardWebhook(');
    expect(samples.python).toContain('verify_standard_webhook(');
    expect(samples.php).toContain('Webhook::verifyStandardWebhook(');
  });

  it('routes the handler on the endpoint’s own path', () => {
    const samples = verifySignatureSnippets({ scheme: 'BOTH', endpointUrl: 'https://shop.example.test/hooks/railhook?x=1' });
    expect(samples.node).toContain("app.post('/hooks/railhook'");
    expect(samples.python).toContain('@app.route("/hooks/railhook"');
  });
});

describe('the one-language snippets', () => {
  it('embeds a portal URL in an iframe', () => {
    expect(portalIframeSnippet('https://x.test/portal#rhp_1').html).toContain('src="https://x.test/portal#rhp_1"');
  });

  it('sends a test webhook to a source’s ingress URL', () => {
    const { curl } = ingressCurlSnippet('https://x.test/ingress/abc', 'X-Signature', 'sha256=');
    expect(curl).toContain('curl -X POST https://x.test/ingress/abc');
    expect(curl).toContain('X-Signature: sha256=<hmac-sha256-hex-of-body>');
  });

  it('logs the CLI in to this deployment before opening a tunnel', () => {
    const { cli } = cliTunnelSnippets({ siteUrl: 'https://site.example.test', apiUrl: BASE });
    expect(cli).toContain('curl -fsSL https://site.example.test/install-cli.sh | bash');
    expect(cli).toContain(`railhook login --server ${BASE}`);
    expect(cli).toContain('railhook listen 3000');
  });
});
