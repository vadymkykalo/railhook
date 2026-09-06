import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import en from '../locales/en.json';

/**
 * Every literal key handed to `t()` exists.
 *
 * The sibling guard, `dynamicKeys.test.ts`, covers keys built by interpolation from a backend
 * enum. This covers the other half, which is larger and was unguarded: a plain string literal
 * that is simply not in the locale files. Nothing else can catch it — `t()` takes a `string`,
 * so TypeScript is satisfied by any spelling; locale parity compares en against uk, and a key
 * missing from both is missing from neither's point of view; and eslint-plugin-i18next asks
 * whether a string went through `t()`, not whether the key on the other side resolves.
 *
 * What reached production: the Settings page renders
 * `t(verified ? 'settings.emailVerified' : 'settings.emailUnverified')`. Only the second half
 * existed, so an account that *was* verified — the normal case — showed the literal text
 * `settings.emailVerified` where its status should be. Two literals in a ternary are invisible
 * to a guard looking for interpolation, and the failing branch is the one nobody tests, because
 * the fixture account is usually unverified.
 *
 * Interpolated keys are skipped here on purpose: they belong to the other file, which knows
 * what values they can take.
 */
const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

/**
 * Every string literal inside a `t(...)` call, not just one sitting immediately after the
 * paren.
 *
 * The first version of this matched `t('` directly and therefore found nothing in
 * `t(verified ? 'a' : 'b')` — which is the exact shape of the bug it was written for. A guard
 * that cannot fail on the case that motivated it is decoration, so this reads the whole
 * argument list instead.
 */
const T_OPEN = /\bt\(/g;
const LITERAL = /(['"])([A-Za-z0-9_.-]+)\1/g;

/** The text between `t(` and its matching `)`, so a nested call cannot cut the scan short. */
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
          // A dotted key is a translation key; a bare word is something else entirely - a
          // format string, a CSS class, an id - and there is no way to tell them apart.
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
