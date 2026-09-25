import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');
const conf = read('railhook-ui/nginx.conf');

function publicRoutes(): { path: string }[] {
  const source = read('railhook-ui/scripts/public-routes.mjs');
  return [...source.matchAll(/\{\s*path:\s*'([^']+)'/g)].map((m) => ({ path: m[1] }));
}

function prerenderedSegments(): string[] {
  return publicRoutes().map((r) => r.path.slice(1).split('/')[0]).filter(Boolean);
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

function routerTopLevelSegments(): string[] {
  const router = read('railhook-ui/src/router.tsx');
  const segments = [...router.matchAll(/path:\s*'\/([^/'*:]+)/g)].map((m) => m[1]);
  return [...new Set(segments)].sort();
}

function spaLocation() {
  return locations().find((l) => l.head.startsWith('~ ^/(') && /index\.html/.test(l.body));
}

function spaSegments(): string[] {
  const head = spaLocation()?.head ?? '';
  const group = head.match(/\^\/\(([^)]+)\)/)?.[1] ?? '';
  return group.split('|').filter(Boolean).sort();
}

/** Every URL used to answer 200 with the landing page, a soft 404 to crawlers. */
describe('nginx answers with the status the URL deserves', () => {
  it('finds the router routes it is meant to be checking', () => {
    expect(routerTopLevelSegments()).toEqual(expect.arrayContaining(['admin', 'login', 'register', 'shared']));
  });

  it('serves the app shell for every top-level route the router owns', () => {
    const prerendered = prerenderedSegments();
    // The portal has its own location because its headers differ.
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
    const prerendered = prerenderedSegments();
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
    // Google's consent screen links these, so they must answer 200 and stay indexable.
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
    // Anchored, no quote, space or semicolon, so a query can't add a directive.
    const patterns = [...map.matchAll(/"(~\*?\^[^"]+\$)"/g)].map((m) => m[1]);
    expect(patterns.length).toBe(4);
    for (const pattern of patterns) {
      expect(pattern).not.toMatch(/\.\*|\\s|;/);
    }
  });
});

describe('sign-in for the MCP server', () => {
  const oauthApi = () => locations().find((l) => l.head.startsWith('~ ^/oauth/'));

  it('sends the OAuth protocol endpoints to the API', () => {
    const api = oauthApi();
    expect(api, 'a regex location for /oauth/{authorize,token,register,revoke}').toBeDefined();
    const pattern = new RegExp(api!.head.replace(/^~\s+/, '').trim());
    for (const path of ['/oauth/authorize', '/oauth/token', '/oauth/register', '/oauth/revoke']) {
      expect(pattern.test(path), path).toBe(true);
    }
    expect(pattern.test('/oauth/consent')).toBe(false);
    expect(api!.body).toMatch(/proxy_pass\s+http:\/\/\$api_backend;/);
    expect(api!.body).toMatch(/proxy_set_header\s+X-Forwarded-Proto\s+\$scheme;/);
  });

  it('checks the API endpoints before the app routes, since nginx takes the first matching regex', () => {
    const heads = locations().map((l) => l.head);
    expect(heads.indexOf(oauthApi()!.head)).toBeLessThan(heads.indexOf(spaLocation()!.head));
  });

  it('serves the consent screen as an app route, kept out of search results', () => {
    expect(spaSegments()).toContain('oauth');
    expect(read('railhook-ui/public/robots.txt')).toMatch(/^Disallow: \/oauth\/$/m);
  });

  it('proxies the discovery metadata ahead of the hidden-file rule', () => {
    const wellKnown = location('^~ /.well-known/oauth-');
    expect(wellKnown, 'a ^~ prefix location, so the `~ /\\.` deny never sees it').toBeDefined();
    expect(wellKnown!.body).toMatch(/proxy_pass\s+http:\/\/\$api_backend;/);
  });
});

describe('the blog switch', () => {
  const blog = () => location('^~ /blog');
  const isBlog = (path: string) => path === '/blog' || path.startsWith('/blog/');
  const paths = (xml: string) =>
    [...xml.matchAll(/<loc>https:\/\/site-url\.railhook\.invalid([^<]*)<\/loc>/g)].map((m) => m[1]);

  it('defaults to off before the entrypoint snippet can turn it on', () => {
    const off = conf.search(/^\s*set \$railhook_blog "off";/m);
    expect(off).toBeGreaterThan(-1);
    expect(conf.search(/^\s*include \/tmp\/railhook-site\.conf;/m)).toBeGreaterThan(off);
  });

  it('answers /blog, every post and the feed 404 when off, with the app shell as the body', () => {
    expect(blog(), 'a ^~ /blog location').toBeDefined();
    expect(blog()!.body).toMatch(/if\s+\(\$railhook_blog\s+!=\s+"on"\)\s*\{\s*return\s+404;\s*\}/);
    expect(blog()!.body).toMatch(/error_page\s+404\s+\/index\.html;/);
  });

  it('serves the blog as `location /` would when on: files, 404 for an unknown slug, the origin rewritten', () => {
    const body = blog()!.body;
    expect(body).toMatch(/try_files\s+\$uri\s+\$uri\/index\.html\s+=404;/);
    expect(body).toMatch(/sub_filter\s+'https:\/\/site-url\.railhook\.invalid'\s+\$railhook_site_url;/);
    expect(body).toMatch(/sub_filter_types\s+[^;]*text\/xml/);
    expect(body).toMatch(/add_header\s+Cache-Control\s+"no-cache, must-revalidate"\s+always;/);
  });

  it('serves the blog-less sitemap as /sitemap.xml unless the blog is on', () => {
    const map = conf.match(/map\s+\$railhook_blog\s+\$railhook_sitemap\s*\{([^}]*)\}/)?.[1] ?? '';
    expect(map).toMatch(/^\s*on\s+\/sitemap\.xml;/m);
    expect(map).toMatch(/^\s*default\s+\/sitemap-without-blog\.xml;/m);

    const sitemap = location('= /sitemap.xml');
    expect(sitemap, 'an exact-match location for /sitemap.xml').toBeDefined();
    expect(sitemap!.body).toMatch(/try_files\s+\$railhook_sitemap\s+=404;/);
    expect(sitemap!.body).toMatch(/sub_filter\s+'https:\/\/site-url\.railhook\.invalid'\s+\$railhook_site_url;/);
    expect(sitemap!.body).toMatch(/sub_filter_types\s+[^;]*text\/xml/);
  });

  it('has a blog-less sitemap to serve: everything else, and nothing of the blog', () => {
    const full = paths(read('railhook-ui/public/sitemap.xml'));
    const without = paths(read('railhook-ui/public/sitemap-without-blog.xml'));
    expect(full).toContain('/blog');
    expect(without.length).toBeGreaterThan(0);
    expect(without).toEqual(full.filter((path) => !isBlog(path)));
  });

  it('swallows no other file in the web root, since `^~ /blog` is a prefix', () => {
    const strays = readdirSync(join(repoRoot, 'railhook-ui/public')).filter((f) => f.startsWith('blog') && f !== 'blog');
    expect(strays).toEqual([]);
  });
});
