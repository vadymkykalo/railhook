// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** Every SVG shipped as a file: the app's public/ and the docs site's public/ and assets. */
const ROOTS = ['railhook-ui/public', 'railhook-docs/public', 'railhook-docs/src/assets'];

function svgFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return svgFiles(path);
    return name.endsWith('.svg') ? [path] : [];
  });
}

/**
 * A standalone SVG is XML, and a browser that meets a well-formedness error renders nothing.
 *
 * The favicon shipped with `--primary` inside a comment, and `--` is not allowed in an XML
 * comment. Inlined into HTML the same markup is forgiven, so it looked right in the app; as
 * /favicon.svg every browser refused it, and the tab went on showing the previous icon out of
 * its cache, which read as a caching problem rather than a broken file.
 */
describe('SVG assets are well-formed XML', () => {
  const files = ROOTS.flatMap((root) => svgFiles(join(repoRoot, root)));

  it('finds the files it is meant to be checking', () => {
    expect(files.map((f) => relative(repoRoot, f))).toContain('railhook-ui/public/favicon.svg');
  });

  it.each(files.map((f) => [relative(repoRoot, f), f]))('%s parses', (_name, path) => {
    const doc = new DOMParser().parseFromString(readFileSync(path, 'utf8'), 'image/svg+xml');
    const error = doc.getElementsByTagName('parsererror')[0];
    expect(error?.textContent ?? '', 'XML parse error').toBe('');
    expect(doc.documentElement.nodeName).toBe('svg');
  });
});
