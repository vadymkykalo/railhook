export type DiffKind = 'same' | 'added' | 'removed';

export interface DiffLine {
  kind: DiffKind;
  text: string;
  leftNumber: number | null;
  rightNumber: number | null;
}

export interface LineDiff {
  lines: DiffLine[];
  added: number;
  removed: number;
}

/** Past the cap it degrades to "everything changed", so a huge output cannot freeze the tab. */
const MAX_LINES_FOR_LCS = 2000;

export function lineDiff(before: string, after: string): LineDiff {
  const left = before.length ? before.split('\n') : [];
  const right = after.length ? after.split('\n') : [];

  if (left.length > MAX_LINES_FOR_LCS || right.length > MAX_LINES_FOR_LCS) {
    return {
      lines: [
        ...left.map((text, i) => ({ kind: 'removed' as const, text, leftNumber: i + 1, rightNumber: null })),
        ...right.map((text, i) => ({ kind: 'added' as const, text, leftNumber: null, rightNumber: i + 1 })),
      ],
      added: right.length,
      removed: left.length,
    };
  }

  const table: number[][] = Array.from({ length: left.length + 1 }, () =>
    new Array<number>(right.length + 1).fill(0));

  for (let i = left.length - 1; i >= 0; i--) {
    for (let j = right.length - 1; j >= 0; j--) {
      table[i][j] = left[i] === right[j]
        ? table[i + 1][j + 1] + 1
        : Math.max(table[i + 1][j], table[i][j + 1]);
    }
  }

  const lines: DiffLine[] = [];
  let added = 0;
  let removed = 0;
  let i = 0;
  let j = 0;

  while (i < left.length && j < right.length) {
    if (left[i] === right[j]) {
      lines.push({ kind: 'same', text: left[i], leftNumber: i + 1, rightNumber: j + 1 });
      i++;
      j++;
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      lines.push({ kind: 'removed', text: left[i], leftNumber: i + 1, rightNumber: null });
      removed++;
      i++;
    } else {
      lines.push({ kind: 'added', text: right[j], leftNumber: null, rightNumber: j + 1 });
      added++;
      j++;
    }
  }
  while (i < left.length) {
    lines.push({ kind: 'removed', text: left[i], leftNumber: i + 1, rightNumber: null });
    removed++;
    i++;
  }
  while (j < right.length) {
    lines.push({ kind: 'added', text: right[j], leftNumber: null, rightNumber: j + 1 });
    added++;
    j++;
  }

  return { lines, added, removed };
}

export function collapseUnchanged(lines: DiffLine[], context = 3): Array<DiffLine | { kind: 'gap'; count: number }> {
  const keep = new Array<boolean>(lines.length).fill(false);
  lines.forEach((line, index) => {
    if (line.kind === 'same') return;
    for (let k = Math.max(0, index - context); k <= Math.min(lines.length - 1, index + context); k++) {
      keep[k] = true;
    }
  });

  const out: Array<DiffLine | { kind: 'gap'; count: number }> = [];
  let skipped = 0;
  lines.forEach((line, index) => {
    if (keep[index]) {
      if (skipped > 0) {
        out.push({ kind: 'gap', count: skipped });
        skipped = 0;
      }
      out.push(line);
    } else {
      skipped++;
    }
  });
  if (skipped > 0) {
    out.push({ kind: 'gap', count: skipped });
  }
  return out;
}
