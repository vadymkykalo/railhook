import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import en from '../locales/en.json';

/** t() takes any string and parity misses keys absent from both; a verified account once showed `settings.emailVerified`. */
const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

/** Reads the whole argument list: `t(verified ? 'a' : 'b')` is the shape of the bug. */
const T_OPEN = /\bt\(/g;
const LITERAL = /(['"])([A-Za-z0-9_.-]+)\1/g;

function callArguments(text: string, from: number): string {
  let depth = 1;
  for (let i = from; i < text.length; i += 1) {
    const c = text[i];
    if (c === '(') depth += 1;
    else if (c === ')') {
      depth -= 1;
      if (depth === 0) return text.slice(from, i);
    }
  }
  return text.slice(from);
}

function sources(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry !== 'node_modules' && entry !== '__tests__') sources(full, out);
    } else if (/\.tsx?$/.test(entry)) {
      out.push(full);
    }
  }
  return out;
}

function resolves(key: string): boolean {
  let node: unknown = en;
  for (const part of key.split('.')) {
    if (typeof node !== 'object' || node === null || !(part in node)) return false;
    node = (node as Record<string, unknown>)[part];
  }
  return typeof node === 'string';
}

describe('static translation keys', () => {
  it('every literal key passed to t() resolves in en.json', () => {
    const missing: string[] = [];

    for (const file of sources(SRC)) {
      const text = readFileSync(file, 'utf8');
      for (const call of text.matchAll(T_OPEN)) {
        const args = callArguments(text, call.index + call[0].length);
        for (const [, , key] of args.matchAll(LITERAL)) {
          // A bare word may be a format string, class or id, not a key.
          if (!key.includes('.') || resolves(key)) continue;
          missing.push(`${file.slice(SRC.length + 1)}: ${key}`);
        }
      }
    }

    expect(missing, `keys used by t() but absent from en.json:\n  ${missing.join('\n  ')}`).toEqual(
      [],
    );
  });
});
