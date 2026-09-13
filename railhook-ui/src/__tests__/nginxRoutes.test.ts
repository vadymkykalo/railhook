import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');
const conf = read('railhook-ui/nginx.conf');

/** The prerendered public paths, as scripts/public-routes.mjs lists them (read as text: it is untyped JS). */
function publicRoutes(): { path: string }[] {
  const source = read('railhook-ui/scripts/public-routes.mjs');
  return [...source.matchAll(/\{\s*path:\s*'([^']+)'/g)].map((m) => ({ path: m[1] }));
}

function locations(): { head: string; body: string }[] {
  const out: { head: string; body: string }[] = [];
  const re = /^\s*location\s+([^{]+)\{/gm;
  let m: RegExpExecArray | null;
  while ((m = re.exec(conf))) {
    let depth = 1;
    let i = re.lastIndex;
    while (depth > 0 && i < conf.length) {
      if (conf[i] === '{') depth++;
      else if (conf[i] === '}') depth--;
      i++;
    }
    out.push({ head: m[1].trim(), body: conf.slice(re.lastIndex, i - 1) });
  }
  return out;
}

const location = (head: string) => locations().find((l) => l.head === head);

/** The first path segment of every absolute route the router declares, `/` and `*` aside. */
function routerTopLevelSegments(): string[] {
  const router = read('railhook-ui/src/router.tsx');
  const segments = [...router.matchAll(/path:\s*'\/([^/'*:]+)/g)].map((m) => m[1]);
  return [...new Set(segments)].sort();
}

/** The SPA location: a regex over the app's own top-level routes. */
function spaLocation() {
  return locations().find((l) => l.head.startsWith('~ ^/(') && /index\.html/.test(l.body));
}

function spaSegments(): string[] {
  const head = spaLocation()?.head ?? '';
  const group = head.match(/\^\/\(([^)]+)\)/)?.[1] ?? '';
  return group.split('|').filter(Boolean).sort();
}

/**
 * Every URL used to answer 200 with the prerendered landing page: `/this-does-not-exist`,
 * `/pricing` (a page that no longer exists) and each dashboard route alike, all carrying the
 * landing's title and a canonical pointing at `/`. To a crawler that is one page duplicated at
 * every address anyone links to — a soft 404 — and `/pricing` was listed in the sitemap on top.
 */
describe('nginx answers with the status the URL deserves', () => {
  it('finds the router routes it is meant to be checking', () => {
    expect(routerTopLevelSegments()).toEqual(expect.arrayContaining(['admin', 'login', 'register', 'shared']));
  });

  it('serves the app shell for every top-level route the router owns', () => {
    const prerendered = publicRoutes().map((r: { path: string }) => r.path.slice(1)).filter(Boolean);
    const owned = routerTopLevelSegments().filter((s) => !prerendered.includes(s) && s !== 'pricing');
    expect(spaLocation(), 'a regex location for the app routes').toBeDefined();
    expect(spaSegments()).toEqual(owned);
  });

  it('keeps the app routes out of search results', () => {
    expect(spaLocation()!.body).toMatch(/add_header\s+X-Robots-Tag\s+"noindex"\s+always;/);
  });

  it('never marks the public pages noindex', () => {
    const prerendered = publicRoutes().map((r: { path: string }) => r.path.slice(1)).filter(Boolean);
    for (const page of prerendered) expect(spaSegments(), page).not.toContain(page);
    expect(location('/')!.body).not.toMatch(/X-Robots-Tag/);
  });

  it('answers an unknown URL with 404, still rendering the app shell', () => {
    const root = location('/')!.body;
    expect(root).toMatch(/try_files\s+\$uri\s+\$uri\/index\.html\s+=404;/);
    // A URI, not `=`: nginx keeps the 404 status while serving the shell's content.
    expect(root).toMatch(/error_page\s+404\s+\/index\.html;/);
  });

  it('keeps rewriting the site origin in the app shell', () => {
    expect(spaLocation()!.body).toMatch(/sub_filter\s+'https:\/\/site-url\.railhook\.invalid'\s+\$railhook_site_url;/);
  });

  it('sends the retired /pricing page where the router sends it, permanently', () => {
    expect(location('= /pricing')?.body).toMatch(/return\s+301\s+\/#run;/);
  });
});

describe('the public route list', () => {
  it('no longer offers /pricing to crawlers or to the prerender', () => {
    expect(publicRoutes().map((r: { path: string }) => r.path)).not.toContain('/pricing');
    expect(read('railhook-ui/public/sitemap.xml')).not.toMatch(/\/pricing</);
  });
});
