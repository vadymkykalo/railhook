export type DiffKind = 'same' | 'added' | 'removed';

export interface DiffLine {
  kind: DiffKind;
  text: string;
  /** 1-based line number on the left, or null for an added line. */
  leftNumber: number | null;
  /** 1-based line number on the right, or null for a removed line. */
  rightNumber: number | null;
}

export interface LineDiff {
  lines: DiffLine[];
  added: number;
  removed: number;
}

/**
 * A line diff over two documents, computed here rather than fetched or imported.
 *
 * `@codemirror/merge` would do this too, and was not worth a dependency: what a
 * transformation's Diff tab shows is two pretty-printed JSON documents that are
 * mostly the same shape, and for that a unified list of added, removed and
 * unchanged lines is both smaller and easier to read than a side-by-side with
 * its own scroll sync.
 *
 * Classic LCS, over lines. The inputs here are one webhook payload each — tens
 * to a few hundred lines — so the quadratic table is a few thousand cells, and
 * the guard below stops a pathological pair (a script that returned a megabyte)
 * from turning a tab switch into a frozen page: past the cap it degrades to
 * "everything changed", which is both true and cheap.
 */
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

  // table[i][j] = length of the longest common subsequence of left[i..] and right[j..]
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

/**
 * Drops long stretches of unchanged lines, keeping `context` on either side of
 * every change. A transformation that renames one field in a 200-line payload
 * should not make you scroll for it.
 */
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
