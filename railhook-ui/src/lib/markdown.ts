/**
 * The Markdown a blog post is written in, read into data the article page renders.
 *
 * Plain data rather than HTML, so `react/no-danger` stays an error across the app.
 *
 * The subset, and nothing else:
 *   `## h2` / `### h3`      — each gets an id, which is what the table of contents links to
 *   ```lang … ```           — a fenced code block, highlighted by `SyntaxHighlight`
 *   `> quote`               — a pull quote
 *   `| a | b |` + `|---|`   — a table with a header row
 *   `- item` / `1. item`    — lists, one level
 *   `:::figure key`         — the figure registered under `key` in `components/blog/figures.tsx`
 *   inline                  — `**strong**`, `*em*`, `` `code` ``, `[text](href)`, `![alt](src)`
 */

export type Inline =
  | { type: 'text'; value: string }
  | { type: 'code'; value: string }
  | { type: 'strong'; children: Inline[] }
  | { type: 'em'; children: Inline[] }
  | { type: 'link'; href: string; children: Inline[] }
  | { type: 'image'; src: string; alt: string };

export interface Heading {
  id: string;
  level: 2 | 3;
  text: string;
}

export type Block =
  | { type: 'heading'; level: 2 | 3; id: string; text: string }
  | { type: 'paragraph'; content: Inline[] }
  | { type: 'list'; ordered: boolean; items: Inline[][] }
  | { type: 'code'; language: string; code: string }
  | { type: 'quote'; content: Inline[] }
  | { type: 'table'; head: Inline[][]; rows: Inline[][][] }
  | { type: 'figure'; key: string };

export interface Document {
  blocks: Block[];
  /** Every `##` and `###`, in document order, for the table of contents. */
  headings: Heading[];
  /** Words of prose, code blocks excluded — what the reading time is estimated from. */
  words: number;
}

const HEADING = /^(#{2,3})\s+(.+?)\s*$/;
const FENCE = /^```([A-Za-z0-9+#-]*)\s*$/;
const BULLET = /^[-*]\s+(.*)$/;
const ORDERED = /^\d+[.)]\s+(.*)$/;
const QUOTE = /^>\s?(.*)$/;
const FIGURE = /^:::figure\s+([A-Za-z0-9-]+)\s*$/;
const TABLE_ROW = /^\|(.*)\|\s*$/;
const TABLE_RULE = /^\|[\s:|-]+\|\s*$/;

/**
 * A heading's anchor. Latin letters, digits and dashes only, so a Ukrainian heading still gets a
 * usable id — the Cyrillic is dropped and the position keeps it unique.
 */
export function slugify(text: string, index: number): string {
  const base = text
    .toLowerCase()
    .replace(/`/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return base || `section-${index + 1}`;
}

/** Pushes text onto the list, merging with a text node already at its end. */
function pushText(out: Inline[], value: string) {
  if (!value) return;
  const last = out[out.length - 1];
  if (last?.type === 'text') last.value += value;
  else out.push({ type: 'text', value });
}

/** A link target a post may point at: this site, another site, or an anchor on the page. */
function safeHref(href: string): string | null {
  const value = href.trim();
  if (/^https?:\/\//i.test(value)) return value;
  if (value.startsWith('#')) return value;
  if (value.startsWith('/') && !value.startsWith('//')) return value;
  return null;
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

    if (rest.startsWith('![')) {
      const match = /^!\[([^\]]*)\]\(([^)\s]+)\)/.exec(rest);
      if (match) {
        const src = safeHref(match[2]);
        if (src) out.push({ type: 'image', src, alt: match[1] });
        i += match[0].length;
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
        const href = safeHref(match[2]);
        const children = parseInline(match[1]);
        if (href) out.push({ type: 'link', href, children });
        else out.push(...children);
        i += match[0].length;
        continue;
      }
    }

    // Plain text up to the next character that could open something.
    const next = rest.slice(1).search(/[`*[!]/);
    const length = next === -1 ? rest.length : next + 1;
    pushText(out, rest.slice(0, length));
    i += length;
  }
  return out;
}

function cells(row: string): Inline[][] {
  return row
    .replace(/^\||\|$/g, '')
    .split('|')
    .map((cell) => parseInline(cell.trim()));
}

/** Words in a run of inline nodes, for the reading-time estimate. */
function countWords(nodes: Inline[]): number {
  return nodes.reduce((total, node) => {
    switch (node.type) {
      case 'text':
      case 'code':
        return total + node.value.split(/\s+/).filter(Boolean).length;
      case 'image':
        return total;
      default:
        return total + countWords(node.children);
    }
  }, 0);
}

export function parseMarkdown(source: string): Document {
  const lines = source.replace(/\r\n/g, '\n').split('\n');
  const blocks: Block[] = [];
  const headings: Heading[] = [];
  let paragraph: string[] = [];

  const flushParagraph = () => {
    if (!paragraph.length) return;
    blocks.push({ type: 'paragraph', content: parseInline(paragraph.join(' ')) });
    paragraph = [];
  };

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];

    if (!line.trim()) {
      flushParagraph();
      continue;
    }

    const fence = FENCE.exec(line);
    if (fence) {
      flushParagraph();
      const code: string[] = [];
      i += 1;
      while (i < lines.length && !/^```\s*$/.test(lines[i])) {
        code.push(lines[i]);
        i += 1;
      }
      blocks.push({ type: 'code', language: fence[1] || 'text', code: code.join('\n') });
      continue;
    }

    const figure = FIGURE.exec(line);
    if (figure) {
      flushParagraph();
      blocks.push({ type: 'figure', key: figure[1] });
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      flushParagraph();
      const level = heading[1].length === 2 ? 2 : 3;
      // Two headings can reduce to the same slug, most easily in Ukrainian, where only the Latin
      // words survive: "Скільки Railhook пробує?" and "Що Railhook гарантує?" are both "railhook".
      // The second then numbers itself, or the contents would link twice to the first.
      const slug = slugify(heading[2], headings.length);
      let id = slug;
      for (let n = 2; headings.some((h) => h.id === id); n++) id = `${slug}-${n}`;
      headings.push({ id, level, text: heading[2] });
      blocks.push({ type: 'heading', level, id, text: heading[2] });
      continue;
    }

    if (TABLE_ROW.test(line) && TABLE_RULE.test(lines[i + 1] ?? '')) {
      flushParagraph();
      const head = cells(line);
      const rows: Inline[][][] = [];
      i += 2;
      while (i < lines.length && TABLE_ROW.test(lines[i])) {
        rows.push(cells(lines[i]));
        i += 1;
      }
      i -= 1;
      blocks.push({ type: 'table', head, rows });
      continue;
    }

    if (QUOTE.test(line)) {
      flushParagraph();
      const quoted: string[] = [];
      while (i < lines.length && QUOTE.test(lines[i])) {
        quoted.push(QUOTE.exec(lines[i])![1].trim());
        i += 1;
      }
      i -= 1;
      blocks.push({ type: 'quote', content: parseInline(quoted.join(' ')) });
      continue;
    }

    if (BULLET.test(line) || ORDERED.test(line)) {
      flushParagraph();
      const ordered = ORDERED.test(line) && !BULLET.test(line);
      const items: Inline[][] = [];
      while (i < lines.length && (BULLET.test(lines[i]) || ORDERED.test(lines[i]))) {
        const match = ordered ? ORDERED.exec(lines[i])! : BULLET.exec(lines[i])!;
        const text = [match[1]];
        // A wrapped bullet continues on an indented line.
        while (/^\s+\S/.test(lines[i + 1] ?? '')) {
          text.push(lines[i + 1].trim());
          i += 1;
        }
        items.push(parseInline(text.join(' ')));
        i += 1;
      }
      i -= 1;
      blocks.push({ type: 'list', ordered, items });
      continue;
    }

    paragraph.push(line.trim());
  }
  flushParagraph();

  const words = blocks.reduce((total, block) => {
    switch (block.type) {
      case 'paragraph':
      case 'quote':
        return total + countWords(block.content);
      case 'heading':
        return total + block.text.split(/\s+/).filter(Boolean).length;
      case 'list':
        return total + block.items.reduce((sum, item) => sum + countWords(item), 0);
      case 'table':
        return (
          total
          + block.head.reduce((sum, cell) => sum + countWords(cell), 0)
          + block.rows.reduce((sum, row) => sum + row.reduce((s, cell) => s + countWords(cell), 0), 0)
        );
      default:
        return total;
    }
  }, 0);

  return { blocks, headings, words };
}
