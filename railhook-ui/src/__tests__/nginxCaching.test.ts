import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const conf = readFileSync(join(repoRoot, 'railhook-ui/nginx.conf'), 'utf8');

/**
 * A year-long `immutable` cache is only right for a file whose name changes when its content does.
 *
 * The static-asset location matched by extension, so the landing's screenshots, the logos, the
 * favicon and og-image — files in public/ with the same name in every release — were sent
 * `immutable` for a year. A browser that had seen the old screenshots kept showing them after a
 * deploy replaced them, without ever asking the server again.
 */
function locations(): { head: string; body: string }[] {
  const out: { head: string; body: string }[] = [];
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
    out.push({ head: m[1].trim(), body: conf.slice(re.lastIndex, i - 1) });
  }
  return out;
}

const HASHED = ['^~ /assets/', '^~ /docs/_astro/'];

describe('nginx caching', () => {
  it('marks only content-hashed paths immutable', () => {
    const immutable = locations().filter((l) => /immutable/.test(l.body)).map((l) => l.head);
    expect(immutable.sort()).toEqual([...HASHED].sort());
  });

  it('makes the extension-matched static files revalidate instead', () => {
    const byExtension = locations().find((l) => l.head.startsWith('~*') && /png/.test(l.head));
    expect(byExtension, 'the static-file location by extension').toBeDefined();
    expect(byExtension!.body).toMatch(/add_header\s+Cache-Control\s+"no-cache"/);
    expect(byExtension!.body).not.toMatch(/expires\s+1y/);
  });
});
