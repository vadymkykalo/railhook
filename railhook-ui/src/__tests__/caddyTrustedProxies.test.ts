// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

function generatedCaddyfile(): string {
  const installer = readFileSync(join(repoRoot, 'install.sh'), 'utf8');
  const open = `cat > "\${INSTALL_DIR}/Caddyfile" <<'CADDY'\n`;
  const start = installer.indexOf(open);
  expect(start, 'write_caddyfile heredoc in install.sh').toBeGreaterThan(-1);
  const end = installer.indexOf('\nCADDY\n', start);
  expect(end).toBeGreaterThan(start);
  return installer.slice(start + open.length, end);
}

function globalOptions(caddyfile: string): string {
  const m = /^\{\n([\s\S]*?)^\}/m.exec(caddyfile);
  expect(m, 'a global options block').not.toBeNull();
  return m![1];
}

/** Caddy's default replaced X-Forwarded-For with the CDN edge, so visitors shared one rate limit. */
describe('Caddyfile written by install.sh', () => {
  it('forwards X-Forwarded-For intact instead of replacing it with the connecting peer', () => {
    const options = globalOptions(generatedCaddyfile());
    expect(options).toMatch(/^\s*servers\s*\{[\s\S]*?^\s*trusted_proxies static 0\.0\.0\.0\/0 ::\/0\s*$/m);
  });
});
