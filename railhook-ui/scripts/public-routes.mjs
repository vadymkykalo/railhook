/**
 * The public URL surface, derived once.
 *
 * Two scripts need this list and they must not disagree: `generate-sitemap.mjs` publishes it
 * to crawlers and `prerender.mjs` renders it to static HTML. A URL in one and not the other is
 * either a page nobody can find or a promise in the sitemap that resolves to an empty shell.
 *
 * The guide routes are read from `src/pages/docs/sections.ts` — the same list the docs sidebar
 * renders — so a guide added to the docs is in the sitemap and prerendered on the next build,
 * and a guide removed cannot leave a 404 behind in either.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const SECTIONS = resolve(here, '../src/pages/docs/sections.ts');

/** The marketing pages, with the priority they deserve relative to each other. */
const MARKETING = [
  { path: '/', priority: '1.0', changefreq: 'weekly' },
  { path: '/pricing', priority: '0.9', changefreq: 'weekly' },
  { path: '/contact', priority: '0.5', changefreq: 'monthly' },
  { path: '/docs', priority: '0.8', changefreq: 'weekly' },
];

/**
 * Pulls the guide ids out of the GUIDE_SECTIONS literal.
 *
 * A regex rather than an import because this file is TypeScript with a `LucideIcon` type
 * import — running it through a transpiler to read a list of string literals would be the more
 * fragile of the two.
 */
function guideIds() {
  const source = readFileSync(SECTIONS, 'utf8');
  const block = source.match(/export const GUIDE_SECTIONS[^[]*\[([\s\S]*?)\n\];/);
  if (!block) throw new Error('GUIDE_SECTIONS not found in sections.ts — did its shape change?');
  const ids = [...block[1].matchAll(/\bid:\s*'([^']+)'/g)].map((m) => m[1]);
  if (ids.length === 0) throw new Error('GUIDE_SECTIONS matched but held no ids');
  return ids;
}

/** Every public route, in the order a sitemap should list them. */
export function publicRoutes() {
  return [
    ...MARKETING,
    // 'overview' is /docs, already listed above.
    ...guideIds()
      .filter((id) => id !== 'overview')
      .map((id) => ({ path: `/docs/${id}`, priority: '0.7', changefreq: 'monthly' })),
    { path: '/docs/api-reference', priority: '0.7', changefreq: 'weekly' },
  ];
}
