import { describe, expect, it } from 'vitest';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { blogNeighbours, blogPost, blogPosts, blogSlugs, BLOG_LOCALES } from '../blog';
import { parseFrontMatter } from '../frontMatter';
import { parseMarkdown, slugify } from '../markdown';
import { FIGURES } from '../../components/blog/figures';

const CONTENT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', 'content', 'blog');
const slugs = readdirSync(CONTENT, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name);

describe('every blog post', () => {
  it('finds the posts it is meant to be checking', () => {
    expect(slugs.length).toBeGreaterThan(0);
    expect(blogSlugs()).toEqual(expect.arrayContaining(slugs));
  });

  it.each(slugs)('%s is written in both languages', (slug) => {
    const files = readdirSync(join(CONTENT, slug));
    for (const locale of BLOG_LOCALES) {
      expect(files, `${slug}/${locale}.md`).toContain(`${locale}.md`);
    }
  });

  it.each(slugs.flatMap((slug) => BLOG_LOCALES.map((locale) => [slug, locale] as const)))(
    '%s/%s.md declares every required front-matter field',
    (slug, locale) => {
      const { values, lists } = parseFrontMatter(readFileSync(join(CONTENT, slug, `${locale}.md`), 'utf8'));
      for (const field of ['title', 'lead', 'description', 'date', 'author']) {
        expect(values[field], `${slug}/${locale}.md: ${field}`).toBeTruthy();
      }
      expect(values.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(lists.tags?.length, `${slug}/${locale}.md: tags`).toBeGreaterThan(0);
    },
  );

  it.each(slugs.flatMap((slug) => BLOG_LOCALES.map((locale) => [slug, locale] as const)))(
    '%s/%s.md names only figures that exist',
    (slug, locale) => {
      const { body } = parseFrontMatter(readFileSync(join(CONTENT, slug, `${locale}.md`), 'utf8'));
      const named = parseMarkdown(body).blocks.filter((block) => block.type === 'figure');
      for (const block of named) {
        expect(Object.keys(FIGURES), `${slug}/${locale}.md places :::figure ${block.key}`).toContain(block.key);
      }
    },
  );

  it.each(slugs)('%s has the social card its og:image points at', (slug) => {
    // Committed, not built: an og:image made only at prerender is missing from local builds.
    const card = resolve(CONTENT, '..', '..', '..', 'public', 'blog', `${slug}.png`);
    expect(existsSync(card), `${slug}.png is missing. Run: npm run blog:og`).toBe(true);
  });

  it.each(slugs)('%s says when its external claims were last checked', (slug) => {
    const post = blogPost(slug, 'en')!;
    const external = post.body.match(/\]\(https?:\/\//g) ?? [];
    if (external.length === 0) return;
    expect(post.sourcesCheckedOn, `${slug} links out but has no sourcesCheckedOn`).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});

describe('blogPosts', () => {
  it('lists posts newest first', () => {
    const dates = blogPosts('en').map((post) => post.date);
    expect([...dates].sort((a, b) => b.localeCompare(a))).toEqual(dates);
  });

  it('serves the reader’s language when the post has one', () => {
    const [first] = blogPosts('uk');
    expect(first.locale).toBe('uk');
    expect(blogPost(first.slug, 'en')!.locale).toBe('en');
    expect(blogPost(first.slug, 'en')!.title).not.toBe(first.title);
  });

  it('falls back to English for a language nobody writes in', () => {
    expect(blogPosts('de')[0].locale).toBe('en');
  });

  it('estimates a reading time of at least a minute', () => {
    for (const post of blogPosts('en')) expect(post.readingMinutes).toBeGreaterThanOrEqual(1);
  });

  it('gives every post the social card named for its slug unless it names another', () => {
    for (const post of blogPosts('en')) expect(post.image).toBe(`/blog/${post.slug}.png`);
  });

  it('returns nothing for an unknown slug', () => {
    expect(blogPost('no-such-post')).toBeUndefined();
    expect(blogNeighbours('no-such-post')).toEqual({});
  });

  it('puts the older post behind and the newer ahead', () => {
    const posts = blogPosts('en');
    const { previous, next } = blogNeighbours(posts[0].slug, 'en');
    expect(next).toBeUndefined();
    expect(previous).toBe(posts[1]);
  });
});

describe('parseMarkdown', () => {
  it('reads headings, and gives each one an anchor', () => {
    const { headings } = parseMarkdown('## First thing\ntext\n### Under it\n');
    expect(headings).toEqual([
      { id: 'first-thing', level: 2, text: 'First thing' },
      { id: 'under-it', level: 3, text: 'Under it' },
    ]);
  });

  it('gives a heading with no Latin letters a positional anchor, so a Ukrainian post still links', () => {
    expect(slugify('Що робить Stripe', 0)).toBe('stripe');
    expect(slugify('Чому саме так', 3)).toBe('section-4');
  });

  it('numbers a heading whose anchor an earlier heading already took', () => {
    const { headings } = parseMarkdown('## Що Railhook гарантує?\n## Скільки Railhook пробує?\n');
    expect(headings.map((h) => h.id)).toEqual(['railhook', 'railhook-2']);
  });

  it('reads a fenced block as code, not as prose', () => {
    const { blocks } = parseMarkdown('```bash\ncurl -X POST https://example.com\n```\n');
    expect(blocks).toEqual([{ type: 'code', language: 'bash', code: 'curl -X POST https://example.com' }]);
  });

  it('reads a pipe table with its header row', () => {
    const { blocks } = parseMarkdown('| A | B |\n|---|---|\n| 1 | 2 |\n');
    expect(blocks[0].type).toBe('table');
    const table = blocks[0] as Extract<typeof blocks[number], { type: 'table' }>;
    expect(table.head).toHaveLength(2);
    expect(table.rows).toHaveLength(1);
  });

  it('reads a figure directive as a slot, not as text', () => {
    expect(parseMarkdown(':::figure retry-ladder\n').blocks).toEqual([{ type: 'figure', key: 'retry-ladder' }]);
  });

  it('reads emphasis, code spans, links and images inline', () => {
    const { blocks } = parseMarkdown('A **bold** `span`, a [link](https://example.com) and ![](/logos/brand/stripe.svg).');
    const kinds = (blocks[0] as Extract<typeof blocks[number], { type: 'paragraph' }>).content.map((n) => n.type);
    expect(kinds).toEqual(expect.arrayContaining(['strong', 'code', 'link', 'image']));
  });

  it('drops a link to a scheme a post has no business using', () => {
    const { blocks } = parseMarkdown('[click](javascript:alert(1))');
    const content = (blocks[0] as Extract<typeof blocks[number], { type: 'paragraph' }>).content;
    expect(content.some((node) => node.type === 'link')).toBe(false);
    expect(content.map((node) => (node.type === 'text' ? node.value : '')).join('')).toContain('click');
  });

  it('counts the words of prose and not of code', () => {
    const prose = parseMarkdown('one two three four five');
    const withCode = parseMarkdown('one two three four five\n\n```js\nconst a = 1; const b = 2; const c = 3;\n```\n');
    expect(prose.words).toBe(5);
    expect(withCode.words).toBe(5);
  });
});
