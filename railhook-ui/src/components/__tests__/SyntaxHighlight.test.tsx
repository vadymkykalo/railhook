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
    const keys = screen.getByTestId('out').querySelectorAll('.tok-key');
    expect([...keys].map((node) => node.textContent)).toContain('"name"');
  });

  it('leaves unknown languages alone', () => {
    expect(highlight('anything at all', 'text')).toEqual(['anything at all']);
  });
});

/** The text of every token the scanner coloured as `kind`, in document order. */
function tokens(code: string, language: Parameters<typeof highlight>[1], kind: string): string[] {
  const { container, unmount } = render(
    <pre>
      <SyntaxHighlight code={code} language={language} />
    </pre>,
  );
  const found = [...container.querySelectorAll(`.tok-${kind}`)].map((node) => node.textContent ?? '');
  unmount();
  return found;
}

const javaSample = `@SchedulerLock(name = "outbox-publisher")
public List<OutboxMessage> claim(int batchSize) {
    // Phase 1: fast claim
    List<OutboxMessage> claimed = txTemplate.execute(status -> repository
            .findPendingBatchForUpdate(OutboxStatus.PENDING.name(), batchSize, 10L));
    return claimed == null ? List.of() : claimed;
}`;

const tsSample = `interface Delivery<T extends object = Record<string, unknown>> {
  readonly id: string;
  payload: T;
}

export async function replay(delivery: Delivery, retries = 3): Promise<void> {
  const pattern = /^evt_[a-z0-9]+$/i;
  if (!pattern.test(delivery.id)) throw new Error(\`bad id \${delivery.id}\`);
  await fetch('/api/v1/deliveries/' + delivery.id, { method: 'POST' });
}`;

const sqlSample = `UPDATE deliveries SET status = 'PROCESSING', version = version + 1
WHERE id = :id AND (next_retry_at IS NULL OR next_retry_at <= now())
RETURNING *`;

const yamlSample = `services:
  worker:
    image: "railhook/worker:2.4.0"  # pinned
    replicas: 2
    healthcheck:
      - enabled: true`;

describe('the IDE grammars', () => {
  it.each([
    ['Java', javaSample, 'java'],
    ['TypeScript', tsSample, 'javascript'],
    ['SQL', sqlSample, 'sql'],
    ['YAML', yamlSample, 'yaml'],
  ] as const)('round-trips a %s sample without losing a character', (_name, code, language) => {
    expect(plainText(code, language)).toBe(code);
  });

  it('maps the fence labels the blog uses', () => {
    expect(normalizeLanguage('java')).toBe('java');
    expect(normalizeLanguage('ts')).toBe('javascript');
    expect(normalizeLanguage('sql')).toBe('sql');
    expect(normalizeLanguage('yml')).toBe('yaml');
    expect(normalizeLanguage('sh')).toBe('bash');
  });

  it('colours a Java fence by kind: annotations, keywords, types and generics, calls, constants', () => {
    expect(tokens(javaSample, 'java', 'annotation')).toEqual(['@SchedulerLock']);
    expect(tokens(javaSample, 'java', 'keyword')).toEqual(expect.arrayContaining(['public', 'return']));
    expect(tokens(javaSample, 'java', 'type')).toEqual(
      expect.arrayContaining(['List', 'OutboxMessage', 'OutboxStatus', 'int']),
    );
    expect(tokens(javaSample, 'java', 'function')).toEqual(
      expect.arrayContaining(['claim', 'execute', 'findPendingBatchForUpdate', 'name', 'of']),
    );
    expect(tokens(javaSample, 'java', 'number')).toEqual(expect.arrayContaining(['PENDING', '10L', 'null']));
    expect(tokens(javaSample, 'java', 'string')).toEqual(['"outbox-publisher"']);
    expect(tokens(javaSample, 'java', 'comment')).toEqual(['// Phase 1: fast claim']);
  });

  it('colours a TypeScript fence: interfaces, generics, primitive types, calls, regex literals', () => {
    expect(tokens(tsSample, 'javascript', 'keyword')).toEqual(
      expect.arrayContaining(['interface', 'extends', 'readonly', 'export', 'async', 'function', 'await']),
    );
    expect(tokens(tsSample, 'javascript', 'type')).toEqual(
      expect.arrayContaining(['Delivery', 'Record', 'string', 'unknown', 'Promise', 'Error']),
    );
    expect(tokens(tsSample, 'javascript', 'function')).toEqual(expect.arrayContaining(['replay', 'test', 'fetch']));
    expect(tokens(tsSample, 'javascript', 'string')).toEqual(
      expect.arrayContaining(['/^evt_[a-z0-9]+$/i', "'/api/v1/deliveries/'"]),
    );
    expect(tokens(tsSample, 'javascript', 'number')).toEqual(expect.arrayContaining(['3']));
  });

  it('colours SQL keywords whatever their case, and its bind parameters', () => {
    expect(tokens(sqlSample, 'sql', 'keyword')).toEqual(
      expect.arrayContaining(['UPDATE', 'SET', 'WHERE', 'AND', 'IS', 'RETURNING']),
    );
    expect(tokens(sqlSample, 'sql', 'variable')).toEqual([':id']);
    expect(tokens(sqlSample, 'sql', 'function')).toEqual(['now']);
    expect(tokens(sqlSample, 'sql', 'string')).toEqual(["'PROCESSING'"]);
  });

  it('colours YAML keys, including one after a list dash, and leaves plain values ink', () => {
    expect(tokens(yamlSample, 'yaml', 'key')).toEqual(['services', 'worker', 'image', 'replicas', 'healthcheck', 'enabled']);
    expect(tokens(yamlSample, 'yaml', 'comment')).toEqual(['# pinned']);
    expect(tokens(yamlSample, 'yaml', 'number')).toEqual(['2', 'true']);
  });

  it('renders code as text — markup in a sample is shown, never parsed', () => {
    const hostile = `String html = "<img src=x onerror=alert(1)>"; // </code><script>alert(2)</script>`;
    for (const language of ['java', 'javascript', 'text', normalizeLanguage('brainfuck')] as const) {
      const { container, unmount } = render(
        <pre>
          <SyntaxHighlight code={hostile} language={language} />
        </pre>,
      );
      expect(container.querySelector('img, script')).toBeNull();
      expect(container.textContent).toBe(hostile);
      unmount();
    }
  });

  it('does not crash on an unterminated string or comment', () => {
    for (const code of ['String s = "never closed', '/* never closed', "SELECT 'open", 'key: "open']) {
      for (const language of ['java', 'javascript', 'sql', 'yaml'] as const) {
        expect(() => highlight(code, language)).not.toThrow();
      }
    }
  });
});
