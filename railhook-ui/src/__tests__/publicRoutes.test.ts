import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p: string) => readFileSync(join(uiRoot, p), 'utf8');

/** The prerendered public paths, as scripts/public-routes.mjs lists them (read as text: it is untyped JS). */
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

  it('is linked from the footer', () => {
    const layout = read('src/layout/PublicLayout.tsx');
    for (const path of publicPaths.filter((p) => p !== '/')) {
      expect(layout, path).toContain(`<RouteLink to="${path}">`);
    }
  });
});
