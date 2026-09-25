import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/** jsdom has no layout, so this reads the source: a flex column stretches a Badge unless it sets alignment. */

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
