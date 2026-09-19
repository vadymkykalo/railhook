/**
 * The `---` block at the top of a blog post, and the body below it.
 *
 * A deliberately small YAML subset — `key: value` and `key: [a, b, c]` — rather than a YAML
 * parser in the browser bundle. The posts are files this repository writes and reviews, their
 * shape is fixed by `BlogPost`, and js-yaml would be a general-purpose parser shipped to every
 * reader for eight scalar fields. A field that wants more than a scalar or a flat list wants to
 * be a component instead.
 *
 * Two callers must agree on it: `src/lib/blog.ts`, which builds the pages, and the `blogRss()`
 * plugin in `vite.config.ts`, which writes the feed at build time. That is why this module
 * imports nothing.
 */

export interface FrontMatter {
  /** Scalars as written, with surrounding quotes removed. */
  values: Record<string, string>;
  /** `key: [a, b, c]` entries. */
  lists: Record<string, string[]>;
  /** Everything after the closing `---`. */
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

/**
 * Splits `source` into its front matter and its body.
 *
 * A file with no front matter is not an error here: `blog.ts` decides a post is unusable, and
 * it can name the field that is missing, which "no front matter" cannot.
 */
export function parseFrontMatter(source: string): FrontMatter {
  // A byte-order mark ahead of the opening `---` would keep the delimiter from matching.
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
