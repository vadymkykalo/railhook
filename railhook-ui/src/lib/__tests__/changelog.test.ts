import { describe, expect, it } from 'vitest';
import { parseChangelog, parseInline, resolveHref, type Block, type Inline } from '../changelog';
import changelog from 'virtual:changelog';

const SAMPLE = `# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- Something not released yet.

## [2.22.0] - 2026-09-18

A short intro paragraph
that wraps onto a second line.

### Added

- **Write to support.** A widget that sends to \`EMAIL_SUPPORT_ADDRESS\`,
  with the visitor as Reply-To.
- **The dashboard:**
  - Several tabs refreshing
    at once.
  - A second child.

#### A smaller heading

### Fixed

- See [the guide](https://example.com/guide) and [upgrading](./UPGRADING.md).

## [2.21.2] - 2026-09-17

### Changed

- **The footer links to the MCP server docs.**

[2.22.0]: https://github.com/vadymkykalo/railhook/compare/v2.21.2...v2.22.0
[2.21.2]: https://github.com/vadymkykalo/railhook/compare/v2.21.1...v2.21.2
`;

function text(inlines: Inline[]): string {
  return inlines
    .map((node) => (node.type === 'text' || node.type === 'code' ? node.value : text(node.children)))
    .join('');
}

describe('parseChangelog', () => {
  const releases = parseChangelog(SAMPLE);

  it('reads each released version with its date, newest first, and skips Unreleased', () => {
    expect(releases.map((r) => [r.version, r.date])).toEqual([
      ['2.22.0', '2026-09-18'],
      ['2.21.2', '2026-09-17'],
    ]);
  });

  it('gives every release an anchor id', () => {
    expect(releases.map((r) => r.id)).toEqual(['v2.22.0', 'v2.21.2']);
  });

  it('joins a paragraph that wraps across lines', () => {
    const intro = releases[0].blocks[0] as Extract<Block, { type: 'paragraph' }>;
    expect(intro.type).toBe('paragraph');
    expect(text(intro.content)).toBe('A short intro paragraph that wraps onto a second line.');
  });

  it('reads section headings and bullets with continuation lines', () => {
    const blocks = releases[0].blocks;
    expect(blocks[1]).toMatchObject({ type: 'heading', level: 3, text: 'Added' });
    const list = blocks[2] as Extract<Block, { type: 'list' }>;
    expect(list.type).toBe('list');
    expect(text(list.items[0].content)).toBe(
      'Write to support. A widget that sends to EMAIL_SUPPORT_ADDRESS, with the visitor as Reply-To.',
    );
  });

  it('nests an indented bullet under the one above it', () => {
    const list = releases[0].blocks[2] as Extract<Block, { type: 'list' }>;
    expect(list.items).toHaveLength(2);
    expect(list.items[1].children.map((c) => text(c.content))).toEqual([
      'Several tabs refreshing at once.',
      'A second child.',
    ]);
  });

  it('keeps a fourth-level heading as a smaller heading', () => {
    expect(releases[0].blocks[3]).toMatchObject({ type: 'heading', level: 4, text: 'A smaller heading' });
  });

  it('ignores the link reference definitions at the end of the file', () => {
    const last = releases[1];
    expect(last.blocks).toHaveLength(2);
    expect(JSON.stringify(last.blocks)).not.toContain('compare/');
  });
});

describe('parseInline', () => {
  it('reads bold, code and links', () => {
    expect(parseInline('**Bold `code` here**, then `x` and [a link](https://a.test/b).')).toEqual([
      { type: 'strong', children: [{ type: 'text', value: 'Bold ' }, { type: 'code', value: 'code' }, { type: 'text', value: ' here' }] },
      { type: 'text', value: ', then ' },
      { type: 'code', value: 'x' },
      { type: 'text', value: ' and ' },
      { type: 'link', href: 'https://a.test/b', children: [{ type: 'text', value: 'a link' }] },
      { type: 'text', value: '.' },
    ]);
  });

  it('reads single-asterisk emphasis but not a lone asterisk', () => {
    expect(parseInline('be *replaced* not 2 * 3')).toEqual([
      { type: 'text', value: 'be ' },
      { type: 'em', children: [{ type: 'text', value: 'replaced' }] },
      { type: 'text', value: ' not 2 * 3' },
    ]);
  });

  it('does not read markup inside code', () => {
    expect(parseInline('`**not bold**`')).toEqual([{ type: 'code', value: '**not bold**' }]);
  });

  it('leaves an unclosed marker as text', () => {
    expect(parseInline('a ** b ` c [d')).toEqual([{ type: 'text', value: 'a ** b ` c [d' }]);
  });
});

describe('resolveHref', () => {
  it('keeps absolute web links and site paths', () => {
    expect(resolveHref('https://example.com/x')).toBe('https://example.com/x');
    expect(resolveHref('/docs/start/quickstart/')).toBe('/docs/start/quickstart/');
  });

  it('points a file in the repository at the repository', () => {
    expect(resolveHref('./UPGRADING.md')).toBe('https://github.com/vadymkykalo/railhook/blob/main/UPGRADING.md');
    expect(resolveHref('docs/OPERATIONS.md#backups')).toBe(
      'https://github.com/vadymkykalo/railhook/blob/main/docs/OPERATIONS.md#backups',
    );
  });

  it('refuses a script URL', () => {
    expect(resolveHref('javascript:alert(1)')).toBeNull();
    expect(resolveHref('data:text/html,x')).toBeNull();
  });
});

describe('the repository changelog', () => {
  const releases = parseChangelog(changelog);

  it('parses into releases, each with a version, a date and content', () => {
    expect(releases.length).toBeGreaterThan(20);
    for (const release of releases) {
      expect(release.version).toMatch(/^\d+\.\d+\.\d+$/);
      expect(release.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(release.blocks.length, release.version).toBeGreaterThan(0);
    }
  });

  it('has unique anchors', () => {
    const ids = releases.map((r) => r.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
