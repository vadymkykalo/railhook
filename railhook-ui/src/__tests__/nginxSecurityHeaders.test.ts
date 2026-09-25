import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

const SNIPPET = '/etc/nginx/snippets/security-headers.conf';
const COMMON = '/etc/nginx/snippets/security-headers-common.conf';

const FRAMEABLE = '= /portal';

/** nginx inherits add_header only into a location that sets none of its own. */
function locationBlocks(conf: string) {
  const blocks: { head: string; body: string }[] = [];
  const re = /^\s*location\s+([^{]+)\{/gm;
  let m: RegExpExecArray | null;
  while ((m = re.exec(conf))) {
    let depth = 1;
    let i = re.lastIndex;
    while (depth > 0 && i < conf.length) {
      if (conf[i] === '{') depth++;
      else if (conf[i] === '}') depth--;
      i++;
    }
    blocks.push({ head: m[1].trim(), body: conf.slice(re.lastIndex, i - 1) });
  }
  return blocks;
}

describe('nginx security headers', () => {
  const conf = read('railhook-ui/nginx.conf');

  it('keeps the headers in snippets the image ships', () => {
    const snippet = read('railhook-ui/nginx-security-headers.conf');
    const common = read('railhook-ui/nginx-security-headers-common.conf');
    expect(snippet).toMatch(/^\s*add_header X-Frame-Options "SAMEORIGIN" always;/m);
    expect(snippet).toMatch(new RegExp(`^\\s*include ${COMMON};`, 'm'));
    for (const header of ['X-Content-Type-Options', 'Referrer-Policy', 'Permissions-Policy']) {
      expect(common, header).toMatch(new RegExp(`^\\s*add_header ${header} `, 'm'));
    }
    const dockerfile = read('railhook-ui/Dockerfile');
    expect(dockerfile).toMatch(new RegExp(`COPY railhook-ui/nginx-security-headers.conf ${SNIPPET}`));
    expect(dockerfile).toMatch(new RegExp(`COPY railhook-ui/nginx-security-headers-common.conf ${COMMON}`));
  });

  it('finds the locations it is meant to be checking', () => {
    expect(locationBlocks(conf).filter((b) => /add_header/.test(b.body)).length).toBeGreaterThanOrEqual(3);
  });

  it('every location that adds a header of its own also includes the security headers', () => {
    const missing = locationBlocks(conf)
      .filter((b) => /^\s*add_header\s/m.test(b.body))
      .filter((b) => !b.body.includes(`include ${b.head === FRAMEABLE ? COMMON : SNIPPET};`))
      .map((b) => b.head);
    expect(missing).toEqual([]);
  });
});
