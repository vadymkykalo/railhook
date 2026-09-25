import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { publicRoutes } from '../../scripts/public-routes.mjs';
import { blogSlugs } from '../lib/blog';

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p: string) => readFileSync(join(uiRoot, p), 'utf8');

/** Posts are reached from /blog, not from footer links of their own. */
const publicPaths = [...read('scripts/public-routes.mjs').matchAll(/\{\s*path:\s*'([^']+)'/g)].map((m) => m[1]);

/** A page missing from any of these is unreachable, unindexed or empty to crawlers. */
describe('every public page', () => {
  it('includes the trust pages and the signature verifier', () => {
    expect(publicPaths).toEqual(expect.arrayContaining(['/security', '/about', '/tools/webhook-signature']));
  });

  it('is a route under the public layout', () => {
    const router = read('src/router.tsx');
    const publicBlock = router.slice(router.indexOf('element: <PublicLayout />'), router.indexOf("path: '/login'"));
    for (const path of publicPaths) expect(publicBlock, path).toContain(`path: '${path}'`);
  });

  it('is in the committed sitemap', () => {
    const sitemap = read('public/sitemap.xml');
    for (const path of publicPaths) expect(sitemap, path).toContain(`${path}</loc>`);
  });

  it('offers the blog index', () => {
    expect(publicPaths).toContain('/blog');
  });

  it('is linked from the footer', () => {
    const layout = read('src/layout/PublicLayout.tsx');
    for (const path of publicPaths.filter((p) => p !== '/')) {
      expect(layout, path).toContain(`<RouteLink to="${path}">`);
    }
  });
});


describe('every blog post', () => {
  const routed = publicRoutes().map((r: { path: string }) => r.path);

  it('has a prerendered, crawlable URL of its own', () => {
    const slugs = blogSlugs();
    expect(slugs.length).toBeGreaterThan(0);
    for (const slug of slugs) expect(routed, slug).toContain(`/blog/${slug}`);
  });

  it('is listed in the committed sitemap', () => {
    const sitemap = read('public/sitemap.xml');
    for (const slug of blogSlugs()) expect(sitemap, slug).toContain(`/blog/${slug}</loc>`);
  });

  it('is listed after the index, newest first, as the blog itself lists them', () => {
    const posts = routed.filter((path: string) => path.startsWith('/blog/'));
    expect(routed.indexOf('/blog')).toBeLessThan(routed.indexOf(posts[0]));
    expect(posts).toEqual(blogSlugs().map((slug) => `/blog/${slug}`));
  });
});
