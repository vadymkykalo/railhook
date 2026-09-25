/** A tiny YAML subset, no imports: the vite RSS plugin reuses it and js-yaml is too big for the bundle. */

export interface FrontMatter {
  values: Record<string, string>;
  lists: Record<string, string[]>;
  body: string;
}

const DELIMITER = /^---\s*$/;

function unquote(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length >= 2 && (trimmed.startsWith('"') || trimmed.startsWith("'"))) {
    const quote = trimmed[0];
    if (trimmed.endsWith(quote)) return trimmed.slice(1, -1);
  }
  return trimmed;
}

export function parseFrontMatter(source: string): FrontMatter {
  // A BOM ahead of `---` would keep the delimiter from matching.
  const lines = source.replace(/^\u{FEFF}/u, '').split(/\r?\n/);
  if (!DELIMITER.test(lines[0] ?? '')) {
    return { values: {}, lists: {}, body: source.trim() };
  }

  const end = lines.findIndex((line, index) => index > 0 && DELIMITER.test(line));
  if (end === -1) return { values: {}, lists: {}, body: source.trim() };

  const values: Record<string, string> = {};
  const lists: Record<string, string[]> = {};

  for (const line of lines.slice(1, end)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    const match = /^([A-Za-z][A-Za-z0-9_]*):\s*(.*)$/.exec(line);
    if (!match) continue;
    const [, key, raw] = match;
    const value = raw.trim();
    if (value.startsWith('[') && value.endsWith(']')) {
      lists[key] = value.slice(1, -1).split(',').map(unquote).filter(Boolean);
      continue;
    }
    values[key] = unquote(value);
  }

  return { values, lists, body: lines.slice(end + 1).join('\n').replace(/^\n+/, '').trimEnd() };
}
