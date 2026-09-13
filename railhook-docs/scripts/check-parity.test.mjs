import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { findParityGaps } from './check-parity.mjs';

function fixture(files) {
  const root = mkdtempSync(join(tmpdir(), 'railhook-parity-'));
  for (const file of files) {
    mkdirSync(dirname(join(root, file)), { recursive: true });
    writeFileSync(join(root, file), '---\ntitle: x\n---\n');
  }
  return root;
}

test('twins in both languages pass', (t) => {
  const root = fixture(['index.mdx', 'uk/index.mdx', 'outgoing/retries.mdx', 'uk/outgoing/retries.mdx']);
  t.after(() => rmSync(root, { recursive: true, force: true }));
  assert.deepEqual(findParityGaps(root), { missingUk: [], missingEn: [] });
});

test('an English page without a Ukrainian twin is reported', (t) => {
  const root = fixture(['index.mdx', 'uk/index.mdx', 'tools/cli.mdx']);
  t.after(() => rmSync(root, { recursive: true, force: true }));
  assert.deepEqual(findParityGaps(root), { missingUk: ['tools/cli'], missingEn: [] });
});

test('a Ukrainian page without an English original is reported', (t) => {
  const root = fixture(['index.mdx', 'uk/index.mdx', 'uk/tools/sdks.mdx']);
  t.after(() => rmSync(root, { recursive: true, force: true }));
  assert.deepEqual(findParityGaps(root), { missingUk: [], missingEn: ['tools/sdks'] });
});

test('.md and .mdx count as the same page', (t) => {
  const root = fixture(['start/concepts.md', 'uk/start/concepts.mdx']);
  t.after(() => rmSync(root, { recursive: true, force: true }));
  assert.deepEqual(findParityGaps(root), { missingUk: [], missingEn: [] });
});

test('the docs in this repository are at parity', () => {
  const docsDir = fileURLToPath(new URL('../src/content/docs', import.meta.url));
  assert.deepEqual(findParityGaps(docsDir), { missingUk: [], missingEn: [] });
});
