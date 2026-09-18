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
 * Every URL used to answer 200 with the prerendered landing page: `/this-does-not-exist` and
 * each dashboard route alike, all carrying the landing's title and a canonical pointing at `/`.
 * To a crawler that is one page duplicated at every address anyone links to — a soft 404.
 */
describe('nginx answers with the status the URL deserves', () => {
  it('finds the router routes it is meant to be checking', () => {
    expect(routerTopLevelSegments()).toEqual(expect.arrayContaining(['admin', 'login', 'register', 'shared']));
  });

  it('serves the app shell for every top-level route the router owns', () => {
    const prerendered = publicRoutes().map((r: { path: string }) => r.path.slice(1)).filter(Boolean);
    // The portal has a location of its own, below, because its headers differ.
    const owned = routerTopLevelSegments().filter((s) => !prerendered.includes(s) && s !== 'portal');
    expect(spaLocation(), 'a regex location for the app routes').toBeDefined();
    expect(spaSegments()).toEqual(owned);
    expect(routerTopLevelSegments()).toContain('portal');
    expect(location('= /portal')!.body).toMatch(/try_files\s+\/index\.html\s+=404;/);
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

  it('serves /pricing as its own page rather than redirecting it to the landing page', () => {
    expect(location('= /pricing')).toBeUndefined();
  });
});

describe('the public route list', () => {
  it('offers /pricing to crawlers and to the prerender', () => {
    expect(publicRoutes().map((r: { path: string }) => r.path)).toContain('/pricing');
    expect(read('railhook-ui/public/sitemap.xml')).toMatch(/\/pricing</);
  });

  it('serves the privacy policy and the terms as prerendered pages, not as 404s or noindex shells', () => {
    // Google's consent screen links both, so they must answer 200 and stay indexable: prerendered
    // into dist/<page>/index.html and served by `location /`, never by the app-route regex.
    const paths = publicRoutes().map((r: { path: string }) => r.path);
    expect(paths).toEqual(expect.arrayContaining(['/privacy', '/terms']));
    expect(spaSegments()).not.toContain('privacy');
    expect(spaSegments()).not.toContain('terms');
    expect(location('/')!.body).toMatch(/try_files\s+\$uri\s+\$uri\/index\.html\s+=404;/);
  });
});

describe('the remote MCP server', () => {
  it('is proxied to the API at /mcp, like /api/', () => {
    const mcp = location('= /mcp');
    expect(mcp, 'an exact-match location for /mcp').toBeDefined();
    expect(mcp!.body).toMatch(/proxy_pass\s+http:\/\/\$api_backend;/);
    expect(mcp!.body).toMatch(/proxy_set_header\s+X-Forwarded-Proto\s+\$scheme;/);
  });
});

/**
 * The customer portal is the one page another site may frame. Every other page keeps
 * X-Frame-Options SAMEORIGIN; the portal drops it for a frame-ancestors taken from the `origin` its
 * URL was issued with, and the portal itself checks that origin against its session.
 */
describe('framing', () => {
  const snippet = read('railhook-ui/nginx-security-headers.conf');
  const common = read('railhook-ui/nginx-security-headers-common.conf');
  const portal = () => location('= /portal')!.body;

  it('keeps every page but the portal unframeable by other sites', () => {
    expect(snippet).toMatch(/add_header\s+X-Frame-Options\s+"SAMEORIGIN"\s+always;/);
    expect(snippet).toMatch(/include\s+\/etc\/nginx\/snippets\/security-headers-common\.conf;/);
    for (const { head, body } of locations()) {
      if (head === '= /portal' || !/add_header/.test(body) || /proxy_pass|deny all/.test(body)) continue;
      expect(body, head).toMatch(/include\s+\/etc\/nginx\/snippets\/security-headers\.conf;/);
    }
  });

  it('serves the portal without X-Frame-Options, and with frame-ancestors instead', () => {
    expect(common).not.toMatch(/add_header\s+X-Frame-Options/);
    expect(portal()).not.toMatch(/security-headers\.conf/);
    expect(portal()).toMatch(/include\s+\/etc\/nginx\/snippets\/security-headers-common\.conf;/);
    expect(portal()).toMatch(/add_header\s+Content-Security-Policy\s+"frame-ancestors \$portal_frame_ancestors"\s+always;/);
  });

  it('never caches the portal shell, and keeps it out of search results', () => {
    expect(portal()).toMatch(/add_header\s+Cache-Control\s+"no-store"\s+always;/);
    expect(portal()).toMatch(/add_header\s+X-Robots-Tag\s+"noindex"\s+always;/);
  });

  it('derives frame-ancestors from the origin only in the shapes an origin has', () => {
    const map = conf.match(/map\s+\$arg_origin\s+\$portal_frame_ancestors\s*\{([\s\S]*?)\n\}/)?.[1] ?? '';
    expect(map).toMatch(/default\s+"https: http:\/\/localhost:\* http:\/\/127\.0\.0\.1:\*";/);
    // Every pattern is anchored and admits no quote, space or semicolon, which is what keeps a
    // crafted query from writing a second directive into the header.
    const patterns = [...map.matchAll(/"(~\*?\^[^"]+\$)"/g)].map((m) => m[1]);
    expect(patterns.length).toBe(4);
    for (const pattern of patterns) {
      expect(pattern).not.toMatch(/\.\*|\\s|;/);
    }
  });
});
