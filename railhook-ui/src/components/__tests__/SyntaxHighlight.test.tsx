import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import SyntaxHighlight, { highlight, normalizeLanguage } from '../SyntaxHighlight';

/**
 * The two things that can silently break here are a token that swallows the rest
 * of the file (an unterminated string alternative) and a scanner that stops
 * emitting text. Both show up as "the code no longer reads as the code", which is
 * why every assertion below is ultimately about lossless round-tripping.
 *
 * The samples are inline on purpose. They used to be imported from the in-app docs, which
 * now live in a site of their own; what matters here is their shape — a JSON body inside
 * shell quotes, a template literal, a PHP heredoc-free script — not where they are shown.
 */

const jsonInShell = `curl -X POST https://your-api.com/api/v1/projects/$PROJECT_ID/rules \\
  -H "X-API-Key: $API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "name": "Route high-value orders",
    "conditions": { "type": "predicate", "field": "data.amount", "operator": "GTE", "value": 1000 },
    "actions": [{ "type": "TAG", "config": { "tag": "high-value" } }]
  }'`;

const eventRequest = `curl -X POST https://your-api.com/api/v1/events \\
  -H "X-API-Key: $API_KEY" \\
  -H "Content-Type: application/json" \\
  -H "Idempotency-Key: order-12345-completed" \\
  -d '{"type":"order.completed","data":{"orderId":"ord_12345","amount":99.99}}'`;

const shellSession = `railhook login

# ▸ Open: http://localhost/device?code=ABCD-1234
# ✓ Logged in as you@company.com`;

const pipedInstall = `curl -fsSL https://railhook.io/install.sh | bash`;

const nodeSample = `import crypto from 'node:crypto';

export function verify(rawBody, header, secret) {
  const parts = Object.fromEntries(header.split(',').map((p) => p.split('=')));
  const expected = crypto
    .createHmac('sha256', secret)
    .update(\`\${parts.t}.\${rawBody}\`)
    .digest('hex');
  return expected === parts.v1;
}`;

const pythonSample = `import hashlib, hmac

def verify(raw_body: bytes, header: str, secret: str) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(","))
    expected = hmac.new(secret.encode(), f"{parts['t']}.".encode() + raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, parts["v1"])`;

const phpSample = `<?php
use Railhook\\Railhook;

$client = new Railhook(apiKey: getenv('RAILHOOK_API_KEY'));

$event = $client->events->send(
    type: 'order.completed',
    data: ['orderId' => 'ord_12345', 'amount' => 99.99],
);`;

function plainText(code: string, language: Parameters<typeof highlight>[1]): string {
  render(
    <pre data-testid="out">
      <SyntaxHighlight code={code} language={language} />
    </pre>,
  );
  return screen.getByTestId('out').textContent ?? '';
}

describe('normalizeLanguage', () => {
  it('maps the labels callers actually pass', () => {
    expect(normalizeLanguage('bash')).toBe('bash');
    expect(normalizeLanguage('curl')).toBe('bash');
    expect(normalizeLanguage('node')).toBe('javascript');
    expect(normalizeLanguage('typescript')).toBe('javascript');
    expect(normalizeLanguage('python')).toBe('python');
    expect(normalizeLanguage('php')).toBe('php');
    expect(normalizeLanguage('http')).toBe('http');
    expect(normalizeLanguage('json')).toBe('json');
  });

  it('falls back to unhighlighted text rather than guessing', () => {
    expect(normalizeLanguage('brainfuck')).toBe('text');
    expect(normalizeLanguage(undefined)).toBe('text');
  });
});

describe('highlight', () => {
  const cases: Array<[string, string, Parameters<typeof highlight>[1]]> = [
    ['a curl with a JSON body', jsonInShell, 'bash'],
    ['a quickstart request', eventRequest, 'bash'],
    ['a shell session with comments', shellSession, 'bash'],
    ['a piped install one-liner', pipedInstall, 'bash'],
    ['a Node sample', nodeSample, 'javascript'],
    ['a Python sample', pythonSample, 'python'],
    ['a PHP sample', phpSample, 'php'],
  ];

  it.each(cases)('renders %s without losing a character', (_name, code, language) => {
    expect(plainText(code, language)).toBe(code);
  });

  it('reuses the same scanner across calls — a leaked lastIndex would drop the first token', () => {
    expect(highlight(jsonInShell, 'bash')).toEqual(highlight(jsonInShell, 'bash'));
  });

  it('highlights the JSON inside a shell-quoted body rather than flattening it', () => {
    render(
      <pre data-testid="out">
        <SyntaxHighlight code={`curl -d '{"name":"Production"}'`} language="bash" />
      </pre>,
    );
    const keys = screen.getByTestId('out').querySelectorAll('.text-primary');
    expect([...keys].map((node) => node.textContent)).toContain('"name"');
  });

  it('leaves unknown languages alone', () => {
    expect(highlight('anything at all', 'text')).toEqual(['anything at all']);
  });
});
