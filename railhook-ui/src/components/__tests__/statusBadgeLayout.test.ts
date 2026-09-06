import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/**
 * A `Badge` is `inline-flex`, so it hugs its label — unless something puts it
 * in a flex column, where the default `align-items: stretch` pulls it to the
 * full width of the cell. That is how a seven-character "Success" came to fill
 * a 245px status column on six tables at once, in a design system whose own
 * brief says status is a pill.
 *
 * <p>jsdom computes no layout, so no rendered test can see the stretch. This
 * reads the source instead: a flex column that directly wraps a StatusBadge has
 * to say how it aligns it.
 */

const SRC = join(__dirname, '..', '..');

function* tsxFiles(dir: string): Generator<string> {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === 'node_modules' || entry === '__tests__') continue;
      yield* tsxFiles(full);
    } else if (entry.endsWith('.tsx')) {
      yield full;
    }
  }
}

describe('status badges are not stretched by their wrapper', () => {
  it('never puts a StatusBadge in a flex column without an alignment', () => {
    const offenders: string[] = [];

    for (const file of tsxFiles(SRC)) {
      const lines = readFileSync(file, 'utf8').split('\n');
      lines.forEach((line, i) => {
        const isFlexColumn = /className="[^"]*\bflex\b[^"]*\bflex-col\b[^"]*"/.test(line);
        const alignsItself = /\bitems-(start|center|end|baseline)\b/.test(line);
        const wrapsABadge = /<StatusBadge|<Badge\b|<EnabledBadge/.test(lines[i + 1] ?? '');

        if (isFlexColumn && !alignsItself && wrapsABadge) {
          offenders.push(`${file.replace(SRC, 'src')}:${i + 1}`);
        }
      });
    }

    expect(offenders, 'add items-start to these wrappers').toEqual([]);
  });
});
