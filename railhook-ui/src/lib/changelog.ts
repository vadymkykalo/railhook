import { REPO_URL } from '../pages/landing/plans';

/**
 * The repository's CHANGELOG.md, read into data the /changelog page renders.
 *
 * Only the subset the file is written in: `## [x.y.z] - date` per release, `###` and `####`
 * headings, paragraphs, `- ` bullets (nested by indentation, wrapped onto continuation lines),
 * and inline `**bold**`, `*emphasis*`, `` `code` `` and `[links](url)`. Not a Markdown library:
 * the page would pay for a general parser to read one file whose shape this repository controls,
 * and the output here is plain data, so nothing from the file is ever inserted as HTML.
 */

export type Inline =
  | { type: 'text'; value: string }
  | { type: 'code'; value: string }
  | { type: 'strong'; children: Inline[] }
  | { type: 'em'; children: Inline[] }
  | { type: 'link'; href: string; children: Inline[] };

export interface ListItem {
  content: Inline[];
  children: ListItem[];
}

export type Block =
  | { type: 'heading'; level: 3 | 4; text: string }
  | { type: 'paragraph'; content: Inline[] }
  | { type: 'list'; items: ListItem[] };

export interface Release {
  version: string;
  date: string;
  /** The anchor the page gives this release, e.g. `v2.22.0`. */
  id: string;
  blocks: Block[];
}

const RELEASE = /^## \[(\d+\.\d+\.\d+)\]\s*-\s*(\d{4}-\d{2}-\d{2})\s*$/;
const HEADING = /^(#{3,4})\s+(.+?)\s*$/;
const BULLET = /^(\s*)[-*]\s+(.*)$/;
const LINK_DEFINITION = /^\[[^\]]+\]:\s+\S+/;

/**
 * Where a link in the file goes on the site. A path inside the repository becomes its page on
 * GitHub; anything that is not a web link or a site path is dropped.
 */
export function resolveHref(href: string): string | null {
  const value = href.trim();
  if (/^https?:\/\//i.test(value)) return value;
  if (value.startsWith('/') && !value.startsWith('//')) return value;
  if (value.startsWith('#')) return value;
  if (/^[a-z][a-z0-9+.-]*:/i.test(value)) return null;
  return `${REPO_URL}/blob/main/${value.replace(/^\.\//, '')}`;
}

/** Pushes text onto the list, merging with a text node already at its end. */
function pushText(out: Inline[], value: string) {
  if (!value) return;
  const last = out[out.length - 1];
  if (last?.type === 'text') last.value += value;
  else out.push({ type: 'text', value });
}

export function parseInline(source: string): Inline[] {
  const out: Inline[] = [];
  let i = 0;
  while (i < source.length) {
    const rest = source.slice(i);

    if (rest.startsWith('`')) {
      const end = source.indexOf('`', i + 1);
      if (end > i + 1) {
        out.push({ type: 'code', value: source.slice(i + 1, end) });
        i = end + 1;
        continue;
      }
    }

    if (rest.startsWith('**')) {
      const end = source.indexOf('**', i + 2);
      if (end > i + 2) {
        out.push({ type: 'strong', children: parseInline(source.slice(i + 2, end)) });
        i = end + 2;
        continue;
      }
    }

    // A single asterisk opens emphasis only against a word, so `2 * 3` stays arithmetic.
    if (rest.startsWith('*') && !rest.startsWith('**') && /^\*\S/.test(rest)) {
      const end = source.indexOf('*', i + 1);
      if (end > i + 1 && !/\s/.test(source[end - 1]) && source[end + 1] !== '*') {
        out.push({ type: 'em', children: parseInline(source.slice(i + 1, end)) });
        i = end + 1;
        continue;
      }
    }

    if (rest.startsWith('[')) {
      const match = /^\[([^\]]+)\]\(([^)\s]+)\)/.exec(rest);
      if (match) {
        const href = resolveHref(match[2]);
        const children = parseInline(match[1]);
        if (href) out.push({ type: 'link', href, children });
        else out.push(...children);
        i += match[0].length;
        continue;
      }
    }

    // Plain text up to the next character that could open something.
    const next = rest.slice(1).search(/[`*[]/);
    const length = next === -1 ? rest.length : next + 1;
    pushText(out, rest.slice(0, length));
    i += length;
  }
  return out;
}

interface RawItem {
  indent: number;
  lines: string[];
  children: RawItem[];
}

function toItems(raw: RawItem[]): ListItem[] {
  return raw.map((item) => ({ content: parseInline(item.lines.join(' ')), children: toItems(item.children) }));
}

/** One release's lines into blocks. */
function parseBlocks(lines: string[]): Block[] {
  const blocks: Block[] = [];
  let paragraph: string[] = [];
  let list: RawItem[] | null = null;
  /** The open items, outermost first, so a continuation line or a deeper bullet finds its parent. */
  let stack: RawItem[] = [];

  const flush = () => {
    if (paragraph.length) blocks.push({ type: 'paragraph', content: parseInline(paragraph.join(' ')) });
    paragraph = [];
    if (list) blocks.push({ type: 'list', items: toItems(list) });
    list = null;
    stack = [];
  };

  for (const line of lines) {
    if (!line.trim()) {
      // A blank line ends a paragraph; a list may carry on past one.
      if (paragraph.length) flush();
      continue;
    }
    if (LINK_DEFINITION.test(line)) continue;

    const heading = HEADING.exec(line);
    if (heading) {
      flush();
      blocks.push({ type: 'heading', level: heading[1].length === 3 ? 3 : 4, text: heading[2] });
      continue;
    }

    const bullet = BULLET.exec(line);
    if (bullet) {
      if (paragraph.length) {
        blocks.push({ type: 'paragraph', content: parseInline(paragraph.join(' ')) });
        paragraph = [];
      }
      const item: RawItem = { indent: bullet[1].length, lines: [bullet[2].trim()], children: [] };
      while (stack.length && stack[stack.length - 1].indent >= item.indent) stack.pop();
      const parent = stack[stack.length - 1];
      if (parent) parent.children.push(item);
      else (list ??= []).push(item);
      stack.push(item);
      continue;
    }

    const indented = /^\s+/.test(line);
    if (stack.length && indented) {
      stack[stack.length - 1].lines.push(line.trim());
      continue;
    }
    if (list) flush();
    paragraph.push(line.trim());
  }
  flush();
  return blocks;
}

export function parseChangelog(markdown: string): Release[] {
  const releases: Release[] = [];
  let current: { version: string; date: string; lines: string[] } | null = null;
  let skipping = false;

  const close = () => {
    if (current) {
      releases.push({
        version: current.version,
        date: current.date,
        id: `v${current.version}`,
        blocks: parseBlocks(current.lines),
      });
    }
    current = null;
  };

  for (const line of markdown.split(/\r?\n/)) {
    if (line.startsWith('## ')) {
      close();
      const match = RELEASE.exec(line);
      // `## [Unreleased]`, or anything else that is not a dated release, is not shown.
      skipping = !match;
      if (match) current = { version: match[1], date: match[2], lines: [] };
      continue;
    }
    if (current && !skipping) current.lines.push(line);
  }
  close();
  // Newest first by version, not by position: the file has a few patch releases below a later minor.
  return releases.sort((a, b) => compareVersions(b.version, a.version));
}

function compareVersions(a: string, b: string): number {
  const x = a.split('.').map(Number);
  const y = b.split('.').map(Number);
  for (let i = 0; i < 3; i += 1) {
    if (x[i] !== y[i]) return x[i] - y[i];
  }
  return 0;
}
