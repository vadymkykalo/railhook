import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { publicRoutes } from '../../scripts/public-routes.mjs';
import { blogSlugs } from '../lib/blog';

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p: string) => readFileSync(join(uiRoot, p), 'utf8');

/**
 * The public paths spelled out in scripts/public-routes.mjs (read as text: it is untyped JS).
 *
 * The blog's articles are not among them — the module enumerates those from the content
 * directory — and that is deliberate: a post is reached from /blog, which is in the footer, not
 * from a footer link of its own.
 */
const publicPaths = [...read('scripts/public-routes.mjs').matchAll(/\{\s*path:\s*'([^']+)'/g)].map((m) => m[1]);

/**
 * A public page lives in four places: the router, the route list the prerender and the sitemap
 * read, the committed sitemap, and the footer that lets a reader find it. A page missing from
 * any one of them is either unreachable, unindexed or rendered empty to a crawler.
 */
describe('every public page', () => {
  it('includes the trust pages and the signature verifier', () => {
    expect(publicPaths).toEqual(expect.arrayContaining(['/security', '/about', '/changelog', '/tools/webhook-signature']));
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


/**
 * A post is a directory under src/content/blog/, and three places have to agree about it: the
 * app (which globs the directory), the sitemap and the prerender (which read the route list).
 * Enumerating rather than hand-listing is what keeps them from drifting — but only if the
 * enumeration actually reaches the same slugs the app does.
 */
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
