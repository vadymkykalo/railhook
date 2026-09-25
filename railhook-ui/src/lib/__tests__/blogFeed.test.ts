// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderFeed, rfc822 } from '../blogFeed';
import { parseFrontMatter } from '../frontMatter';

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(uiRoot, p), 'utf8');

const SITE = 'https://site-url.railhook.invalid';

const ITEMS = [
  { slug: 'newer', title: 'Tom & Jerry <script>', description: 'Two "characters".', date: '2026-09-19', author: 'Vadym Kykalo' },
  { slug: 'older', title: 'The first one', description: 'Older.', date: '2026-01-02' },
];

describe('the blog feed', () => {
  const xml = renderFeed(SITE, ITEMS);

  it('is a well-formed RSS 2.0 document', () => {
    expect(xml.startsWith('<?xml version="1.0" encoding="UTF-8"?>')).toBe(true);
    expect(xml).toContain('<rss version="2.0"');
    expect(xml.trimEnd().endsWith('</rss>')).toBe(true);
  });

  it('points at itself, so a reader that found it through a link can subscribe to it', () => {
    expect(xml).toContain(`<atom:link href="${SITE}/blog/rss.xml" rel="self" type="application/rss+xml"/>`);
    expect(xml).toContain(`<link>${SITE}/blog</link>`);
  });

  it('carries one item per post, newest first, each with a permanent link', () => {
    expect(xml.indexOf('<title>Tom &amp; Jerry')).toBeLessThan(xml.indexOf('<title>The first one'));
    expect(xml).toContain(`<guid isPermaLink="true">${SITE}/blog/newer</guid>`);
    expect(xml).toContain(`<pubDate>${rfc822('2026-09-19')}</pubDate>`);
    // Read as UTC: west of Greenwich a local parse dates every post a day early.
    expect(rfc822('2026-09-19')).toBe('Sat, 19 Sep 2026 00:00:00 GMT');
  });

  it('escapes the markup a title could otherwise inject', () => {
    expect(xml).toContain('Tom &amp; Jerry &lt;script&gt;');
    expect(xml).not.toContain('<script>');
    expect(xml).toContain('Two &quot;characters&quot;.');
  });

  it('names the author where there is one, under a namespace it declares', () => {
    expect(xml).toContain('xmlns:dc="http://purl.org/dc/elements/1.1/"');
    expect(xml).toContain('<dc:creator>Vadym Kykalo</dc:creator>');
    // No email address is published, so RSS <author> is never written.
    expect(xml).not.toContain('<author>');
  });

  it('says nothing about a build date when there is nothing to date', () => {
    expect(renderFeed(SITE, [])).not.toContain('lastBuildDate');
  });
});

describe('the feed is published at /blog/rss.xml', () => {
  const config = read('vite.config.ts');

  it('is emitted into the build at that path', () => {
    expect(config).toContain("fileName: 'blog/rss.xml'");
    expect(config).toContain("const SITE = 'https://site-url.railhook.invalid'");
  });

  it('is served by the dev server from the same path', () => {
    expect(config).toContain("req.url?.split('?')[0] !== '/blog/rss.xml'");
    expect(config).toContain("'application/rss+xml; charset=utf-8'");
  });

  it('is rewritten to the deployment’s own origin when nginx serves it', () => {
    const conf = readFileSync(join(uiRoot, 'nginx.conf'), 'utf8');
    const root = conf.slice(conf.lastIndexOf('location / {'));
    expect(root).toMatch(/sub_filter_types text\/xml application\/xml text\/plain;/);
  });

  it('reads its posts through the same front-matter parser the pages do', () => {
    expect(config).toContain("import { parseFrontMatter } from './src/lib/frontMatter'");
    const post = parseFrontMatter(read('src/content/blog/stripe-github-shopify-when-your-endpoint-is-down/en.md'));
    expect(post.values.title).toBeTruthy();
    expect(post.values.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
