// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** The Caddyfile install.sh writes, as the heredoc in write_caddyfile. */
function generatedCaddyfile(): string {
  const installer = readFileSync(join(repoRoot, 'install.sh'), 'utf8');
  const open = `cat > "\${INSTALL_DIR}/Caddyfile" <<'CADDY'\n`;
  const start = installer.indexOf(open);
  expect(start, 'write_caddyfile heredoc in install.sh').toBeGreaterThan(-1);
  const end = installer.indexOf('\nCADDY\n', start);
  expect(end).toBeGreaterThan(start);
  return installer.slice(start + open.length, end);
}

/** The global options block: the first `{ … }` at column zero. */
function globalOptions(caddyfile: string): string {
  const m = /^\{\n([\s\S]*?)^\}/m.exec(caddyfile);
  expect(m, 'a global options block').not.toBeNull();
  return m![1];
}

/**
 * Who the API believes the client is.
 *
 * The API walks X-Forwarded-For from the right and stops at the first hop that is not in
 * WEBHOOK_TRUSTED_PROXIES — the one place that decision is made. Caddy, left at its default,
 * trusts no upstream and replaces the header with the address of whoever connected to it. Behind
 * a CDN that address is the CDN's edge, so every visitor through one edge shared one sign-in rate
 * limit: found on production, where approving a CLI login answered 429. Caddy has to pass the
 * chain on intact and leave the choice to the API.
 */
describe('Caddyfile written by install.sh', () => {
  it('forwards X-Forwarded-For intact instead of replacing it with the connecting peer', () => {
    const options = globalOptions(generatedCaddyfile());
    expect(options).toMatch(/^\s*servers\s*\{[\s\S]*?^\s*trusted_proxies static 0\.0\.0\.0\/0 ::\/0\s*$/m);
  });
});
