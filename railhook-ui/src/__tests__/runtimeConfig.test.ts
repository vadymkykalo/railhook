// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Every character a RegExp treats specially, backslash included. */
const escapeRegExp = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

const SCRIPT = 'railhook-ui/docker-entrypoint.d/20-runtime-config.sh';
const OUT = '/tmp/railhook-config.js';
const SITE_CONF = '/tmp/railhook-site.conf';
const SNIPPET = '/etc/nginx/snippets/security-headers.conf';
const PLACEHOLDER = 'https://site-url.railhook.invalid';
const TURNSTILE = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

/**
 * What differs between deployments is a property of the container, not of the image.
 *
 * The published image is built once and runs both railhook.io and every self-hosted install.
 * The contact domain, the public origin and the registration challenge used to be VITE_ values
 * inlined at build time, so railhook.io ran a second, hand-built image — and `railhook upgrade`
 * moved the API to the new release while that image stayed a release behind.
 *
 * So the UI container writes `window.__RAILHOOK__` at startup, nginx serves it as /config.js,
 * and index.html loads it before the app; the origin also reaches nginx, which substitutes it
 * for the placeholder the build leaves in the HTML, the sitemaps and robots.txt. Each hop is
 * asserted here, because any one missing fails silently.
 */

type Config = {
  contactDomain?: string;
  siteUrl?: string;
  captchaSiteKey?: string;
  captchaScriptUrl?: string;
  webAnalyticsToken?: string;
  statusPageUrl?: string;
  publicTester?: boolean;
  publicDemo?: boolean;
  publicBlog?: boolean;
};

const EMPTY: Config = {
  contactDomain: '', siteUrl: '', captchaSiteKey: '', captchaScriptUrl: '', webAnalyticsToken: '', statusPageUrl: '', publicTester: false,
  publicDemo: false, publicBlog: false,
};

/** Runs the entrypoint script as the container would, and evaluates what it wrote. */
function runEntrypoint(env: Record<string, string | undefined>) {
  const dir = mkdtempSync(join(tmpdir(), 'railhook-runtime-config-'));
  const out = join(dir, 'railhook-config.js');
  const siteConfOut = join(dir, 'railhook-site.conf');
  const childEnv: Record<string, string> = {
    PATH: process.env.PATH ?? '/usr/bin:/bin',
    RAILHOOK_RUNTIME_CONFIG_OUT: out,
    RAILHOOK_SITE_CONF_OUT: siteConfOut,
  };
  for (const [k, v] of Object.entries(env)) if (v !== undefined) childEnv[k] = v;
  const result = spawnSync('sh', [join(repoRoot, SCRIPT)], { env: childEnv, encoding: 'utf8' });
  const js = existsSync(out) ? readFileSync(out, 'utf8') : '';
  const siteConf = existsSync(siteConfOut) ? readFileSync(siteConfOut, 'utf8') : '';
  rmSync(dir, { recursive: true, force: true });
  const window: { __RAILHOOK__?: Config } = {};
  if (js) new Function('window', js)(window);
  return { status: result.status, stdout: result.stdout, stderr: result.stderr, js, siteConf, config: window.__RAILHOOK__ };
}

/** The snippet's origin line; the blog switch follows it on a line of its own. */
const originLine = (siteConf: string) => siteConf.split('\n')[0];

function locationBody(conf: string, head: string): string | undefined {
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
    if (m[1].trim() === head) return conf.slice(re.lastIndex, i - 1);
  }
  return undefined;
}

describe('the entrypoint writes the runtime config', () => {
  it('writes to /tmp by default, which is writable under a read-only root filesystem', () => {
    const script = read(SCRIPT);
    expect(script).toMatch(/^#!\/bin\/sh/);
    expect(script).toContain(`\${RAILHOOK_RUNTIME_CONFIG_OUT:-${OUT}}`);
    expect(script).toContain(`\${RAILHOOK_SITE_CONF_OUT:-${SITE_CONF}}`);
    expect(statSync(join(repoRoot, SCRIPT)).mode & 0o111, 'the script must be executable').not.toBe(0);
  });

  it('always writes both files, empty when nothing is set, so /config.js never 404s', () => {
    for (const value of [undefined, '']) {
      const { status, config, siteConf } = runEntrypoint({
        RAILHOOK_CONTACT_DOMAIN: value,
        RAILHOOK_SITE_URL: value,
        RAILHOOK_CAPTCHA_SITE_KEY: value,
      });
      expect(status).toBe(0);
      expect(config).toEqual(EMPTY);
      expect(originLine(siteConf)).toBe('set $railhook_site_url "";');
    }
  });

  it('is copied into the image, where the base entrypoint runs it before nginx', () => {
    const dockerfile = read('railhook-ui/Dockerfile');
    expect(dockerfile).toMatch(/COPY railhook-ui\/docker-entrypoint\.d\/20-runtime-config\.sh \/docker-entrypoint\.d\/20-runtime-config\.sh/);
    expect(dockerfile).toMatch(/chmod \+x[^\n]*\/docker-entrypoint\.d\/20-runtime-config\.sh/);
  });
});

describe('contact domain', () => {
  it('carries RAILHOOK_CONTACT_DOMAIN into window.__RAILHOOK__, trimmed', () => {
    expect(runEntrypoint({ RAILHOOK_CONTACT_DOMAIN: 'railhook.io' }).config).toEqual({ ...EMPTY, contactDomain: 'railhook.io' });
    expect(runEntrypoint({ RAILHOOK_CONTACT_DOMAIN: '  example.org \t' }).config?.contactDomain).toBe('example.org');
  });

  it('refuses anything that is not a hostname, and says so without failing the container', () => {
    for (const value of ['example.org"};alert(1);//', 'exa mple.org', 'mail@example.org', '<script>', 'a.org\nb.org']) {
      const { status, stderr, js, config } = runEntrypoint({ RAILHOOK_CONTACT_DOMAIN: value });
      expect(status, value).toBe(0);
      expect(config, value).toEqual(EMPTY);
      expect(js, value).not.toContain('alert');
      expect(stderr, value).toMatch(/RAILHOOK_CONTACT_DOMAIN/);
    }
  });
});

describe('public origin', () => {
  it('reaches both the page and nginx, with trailing slashes stripped', () => {
    const { status, config, siteConf } = runEntrypoint({ RAILHOOK_SITE_URL: ' https://railhook.io/ ' });
    expect(status).toBe(0);
    expect(config?.siteUrl).toBe('https://railhook.io');
    expect(originLine(siteConf)).toBe('set $railhook_site_url "https://railhook.io";');
  });

  it('accepts http and a port, which is what a self-hosted install on its own box has', () => {
    expect(runEntrypoint({ RAILHOOK_SITE_URL: 'http://localhost:8080' }).config?.siteUrl).toBe('http://localhost:8080');
  });

  it('refuses anything but an origin, so nothing can break out of the JS string or the nginx directive', () => {
    for (const value of ['railhook.io', 'https://railhook.io/app', 'ftp://railhook.io', 'https://x.io";set $a 1;#', 'https://x.io\nhttps://y.io', 'https://']) {
      const { status, stderr, config, siteConf } = runEntrypoint({ RAILHOOK_SITE_URL: value });
      expect(status, value).toBe(0);
      expect(config?.siteUrl, value).toBe('');
      expect(originLine(siteConf), value).toBe('set $railhook_site_url "";');
      expect(stderr, value).toMatch(/RAILHOOK_SITE_URL/);
    }
  });
});

describe('registration challenge', () => {
  it('turns on with a site key, using Turnstile unless told otherwise', () => {
    const { config, stdout } = runEntrypoint({ RAILHOOK_CAPTCHA_SITE_KEY: '0x4AAAAAAB_test-key' });
    expect(config).toEqual({ ...EMPTY, captchaSiteKey: '0x4AAAAAAB_test-key', captchaScriptUrl: TURNSTILE });
    expect(stdout).toMatch(/captcha on/);
    expect(stdout, 'the key itself stays out of the log').not.toContain('0x4AAAAAAB_test-key');
  });

  it('takes another provider script, which is how hCaptcha is chosen', () => {
    const url = 'https://js.hcaptcha.com/1/api.js?render=explicit';
    expect(runEntrypoint({ RAILHOOK_CAPTCHA_SITE_KEY: 'key', RAILHOOK_CAPTCHA_SCRIPT_URL: url }).config?.captchaScriptUrl).toBe(url);
  });

  it('publishes no script without a key, since it would load a third party for nothing', () => {
    expect(runEntrypoint({ RAILHOOK_CAPTCHA_SCRIPT_URL: TURNSTILE }).config).toEqual(EMPTY);
  });

  it('stays off, rather than failing the container, on a value that could break out of the config', () => {
    for (const env of [
      { RAILHOOK_CAPTCHA_SITE_KEY: 'key"};alert(1);//' },
      { RAILHOOK_CAPTCHA_SITE_KEY: 'key', RAILHOOK_CAPTCHA_SCRIPT_URL: 'http://insecure.example/api.js' },
      { RAILHOOK_CAPTCHA_SITE_KEY: 'key', RAILHOOK_CAPTCHA_SCRIPT_URL: 'https://x.example/"};alert(1);//' },
    ]) {
      const { status, js, config, stderr } = runEntrypoint(env);
      expect(status).toBe(0);
      expect(config).toEqual(EMPTY);
      expect(js).not.toContain('alert');
      expect(stderr).toMatch(/RAILHOOK_CAPTCHA_/);
    }
  });
});

describe('public webhook tester', () => {
  it('is on only for an exact "true", so a self-hosted install opens nothing anonymous', () => {
    expect(runEntrypoint({ RAILHOOK_PUBLIC_TESTER: 'true' }).config?.publicTester).toBe(true);
    for (const value of [undefined, '', 'false', 'yes', '1', 'true"};alert(1);//']) {
      const { config, js } = runEntrypoint({ RAILHOOK_PUBLIC_TESTER: value });
      expect(config?.publicTester, String(value)).toBe(false);
      expect(js).not.toContain('alert');
    }
  });

  it('reads the same switch as the API in Compose, and is off in Helm unless set', () => {
    const compose = read('docker-compose.yml');
    const ui = compose.slice(compose.indexOf('\n  ui:'), compose.indexOf('\n  caddy:'));
    const api = compose.slice(compose.indexOf('\n  api:'), compose.indexOf('\n  worker:'));
    expect(ui).toMatch(/^\s+RAILHOOK_PUBLIC_TESTER: \$\{PUBLIC_TESTER_ENABLED:-false\}$/m);
    expect(api).toMatch(/^\s+PUBLIC_TESTER_ENABLED: \$\{PUBLIC_TESTER_ENABLED:-false\}$/m);
    expect(read('deploy/helm/railhook/templates/ui-deployment.yaml'))
      .toMatch(/name: RAILHOOK_PUBLIC_TESTER\s+value: \{\{ \.Values\.ui\.publicTester \| default false \| quote \}\}/);
    expect(read('deploy/helm/railhook/values.yaml')).toMatch(/^ {2}publicTester: false$/m);
    expect(read('.env.dist')).toMatch(/^#\s*PUBLIC_TESTER_ENABLED=false$/m);
  });
});

describe('live demo', () => {
  it('is on only for an exact "true", so a self-hosted install opens nothing anonymous', () => {
    expect(runEntrypoint({ RAILHOOK_PUBLIC_DEMO: 'true' }).config?.publicDemo).toBe(true);
    for (const value of [undefined, '', 'false', 'yes', '1', 'true"};alert(1);//']) {
      const { config, js } = runEntrypoint({ RAILHOOK_PUBLIC_DEMO: value });
      expect(config?.publicDemo, String(value)).toBe(false);
      expect(js).not.toContain('alert');
    }
  });

  it('reads the same switch as the API in Compose, and is off in Helm unless set', () => {
    const compose = read('docker-compose.yml');
    const ui = compose.slice(compose.indexOf('\n  ui:'), compose.indexOf('\n  caddy:'));
    const api = compose.slice(compose.indexOf('\n  api:'), compose.indexOf('\n  worker:'));
    expect(ui).toMatch(/^\s+RAILHOOK_PUBLIC_DEMO: \$\{DEMO_ENABLED:-false\}$/m);
    expect(api).toMatch(/^\s+DEMO_ENABLED: \$\{DEMO_ENABLED:-false\}$/m);
    expect(read('deploy/helm/railhook/templates/ui-deployment.yaml'))
      .toMatch(/name: RAILHOOK_PUBLIC_DEMO\s+value: \{\{ \.Values\.ui\.publicDemo \| default false \| quote \}\}/);
    expect(read('deploy/helm/railhook/values.yaml')).toMatch(/^ {2}publicDemo: false$/m);
    expect(read('.env.dist')).toMatch(/^#\s*DEMO_ENABLED=false$/m);
  });
});

/**
 * The blog is railhook.io's own content. The image carries it because railhook.io runs the same
 * image as every self-hosted install, so it is off unless the deployment turns it on — in the
 * page, which hides the links, and in nginx, which answers 404 for the pages and the feed.
 */
describe('blog', () => {
  it('is off by default, in the page and in nginx', () => {
    const { config, siteConf, stdout } = runEntrypoint({});
    expect(config?.publicBlog).toBe(false);
    expect(siteConf).toMatch(/^set \$railhook_blog "off";$/m);
    expect(stdout).toMatch(/blog off/);
  });

  it('is on only for an exact "true"', () => {
    const on = runEntrypoint({ RAILHOOK_PUBLIC_BLOG: ' true ' });
    expect(on.config).toEqual({ ...EMPTY, publicBlog: true });
    expect(on.siteConf).toMatch(/^set \$railhook_blog "on";$/m);
    for (const value of [undefined, '', 'false', 'yes', '1', 'TRUE', 'true"};alert(1);//', 'true";set $a 1;#']) {
      const { config, js, siteConf } = runEntrypoint({ RAILHOOK_PUBLIC_BLOG: value });
      expect(config?.publicBlog, String(value)).toBe(false);
      expect(js).not.toContain('alert');
      expect(siteConf.trim().split('\n'), String(value)).toEqual(['set $railhook_site_url "";', 'set $railhook_blog "off";']);
    }
  });

  it('is BLOG_ENABLED in Compose and ui.publicBlog in Helm, off unless set', () => {
    const compose = read('docker-compose.yml');
    const ui = compose.slice(compose.indexOf('\n  ui:'), compose.indexOf('\n  caddy:'));
    expect(ui).toMatch(/^\s+RAILHOOK_PUBLIC_BLOG: \$\{BLOG_ENABLED:-false\}$/m);
    expect(read('deploy/helm/railhook/templates/ui-deployment.yaml'))
      .toMatch(/name: RAILHOOK_PUBLIC_BLOG\s+value: \{\{ \.Values\.ui\.publicBlog \| default false \| quote \}\}/);
    expect(read('deploy/helm/railhook/values.yaml')).toMatch(/^ {2}publicBlog: false$/m);
    expect(read('.env.dist')).toMatch(/^#\s*BLOG_ENABLED=false$/m);
  });

  it('is rendered on by the prerender, whose pages are railhook.io\'s', () => {
    expect(read('railhook-ui/scripts/prerender.mjs')).toMatch(/^\s+publicBlog: true,$/m);
    expect(read('railhook-ui/public/config.js')).toMatch(/publicBlog: false/);
  });
});

describe('web analytics', () => {
  it('turns on with a Cloudflare Web Analytics token, which stays out of the log', () => {
    const token = '0123456789abcdef0123456789abcdef';
    const { config, stdout } = runEntrypoint({ RAILHOOK_WEB_ANALYTICS_TOKEN: ` ${token} ` });
    expect(config).toEqual({ ...EMPTY, webAnalyticsToken: token });
    expect(stdout).toMatch(/web analytics on/);
    expect(stdout).not.toContain(token);
  });

  it('is off by default, so a self-hosted install reports nothing to anyone', () => {
    const { config, stdout } = runEntrypoint({});
    expect(config?.webAnalyticsToken).toBe('');
    expect(stdout).toMatch(/web analytics off/);
  });

  it('stays off, rather than failing the container, on a value that could break out of the config', () => {
    const { status, js, config, stderr } = runEntrypoint({ RAILHOOK_WEB_ANALYTICS_TOKEN: 'abc"};alert(1);//' });
    expect(status).toBe(0);
    expect(config).toEqual(EMPTY);
    expect(js).not.toContain('alert');
    expect(stderr).toMatch(/RAILHOOK_WEB_ANALYTICS_TOKEN/);
  });

  it('is loaded by the app and by the docs, after the runtime config it reads', () => {
    const html = read('railhook-ui/index.html');
    const config = html.search(/<script src="\/config\.js"><\/script>/);
    const analytics = html.search(/<script src="\/analytics\.js" defer><\/script>/);
    expect(analytics, '<script src="/analytics.js" defer>').toBeGreaterThan(config);
    const docs = read('railhook-docs/astro.config.mjs');
    expect(docs).toMatch(/src: '\/config\.js'/);
    expect(docs).toMatch(/src: '\/analytics\.js'/);
  });
});

describe('nginx serves the runtime config', () => {
  const conf = read('railhook-ui/nginx.conf');
  const body = locationBody(conf, '= /config.js');

  it('from the file the entrypoint wrote, in an exact-match location', () => {
    // Exact match, so it wins over the static-file regex and over the build-time
    // public/config.js that dist/ also carries.
    expect(body, 'location = /config.js').toBeDefined();
    expect(body).toMatch(new RegExp(`^\\s*alias\\s+${escapeRegExp(OUT)};`, 'm'));
  });

  it('never cached, since it changes with the container rather than the release', () => {
    expect(body).toMatch(/add_header\s+Cache-Control\s+"no-cache"\s+always;/);
  });

  it('with the security headers a location that adds its own header would otherwise drop', () => {
    expect(body).toContain(`include ${SNIPPET};`);
  });
});

describe('nginx substitutes the public origin', () => {
  const conf = read('railhook-ui/nginx.conf');

  it('defines the blog switch off, then includes the value the entrypoint wrote over it', () => {
    const defaults = conf.search(/^\s*set \$railhook_blog "off";/m);
    const include = conf.search(new RegExp(`^\\s*include ${escapeRegExp(SITE_CONF)};`, 'm'));
    expect(defaults, 'default').toBeGreaterThan(-1);
    expect(include, 'include').toBeGreaterThan(defaults);
  });

  it('defines the variable, then includes the value the entrypoint wrote over it', () => {
    const defaults = conf.search(/^\s*set \$railhook_site_url "";/m);
    const include = conf.search(new RegExp(`^\\s*include ${escapeRegExp(SITE_CONF)};`, 'm'));
    expect(defaults, 'default').toBeGreaterThan(-1);
    expect(include, 'include').toBeGreaterThan(defaults);
  });

  it.each(['/', '/docs/'])('in location %s, for pages, sitemaps and robots.txt', (head) => {
    const body = locationBody(conf, head);
    expect(body, `location ${head}`).toBeDefined();
    expect(body).toMatch(new RegExp(`sub_filter\\s+'${escapeRegExp(PLACEHOLDER)}'\\s+\\$railhook_site_url;`));
    expect(body).toMatch(/sub_filter_once\s+off;/);
    expect(body).toMatch(/sub_filter_types\s+[^;]*text\/xml[^;]*text\/plain/);
  });

  it('serves the release as /version.txt, never cached, so a deploy can check what is live', () => {
    const body = locationBody(conf, '= /version.txt');
    expect(body, 'location = /version.txt').toBeDefined();
    expect(body).toMatch(/add_header\s+Cache-Control\s+"no-cache"\s+always;/);
    expect(body).toContain(`include ${SNIPPET};`);
    expect(read('railhook-ui/Dockerfile')).toMatch(/require\('\.\/package\.json'\)\.version"\s*>\s*dist\/version\.txt/);
  });
});

describe('the build leaves the placeholder origin, and nothing else, where an origin goes', () => {
  it.each([
    'railhook-ui/index.html',
    'railhook-ui/public/robots.txt',
    'railhook-ui/public/sitemap.xml',
    'railhook-ui/public/sitemap-without-blog.xml',
    'railhook-ui/scripts/generate-sitemap.mjs',
    'railhook-ui/scripts/prerender.mjs',
    'railhook-docs/astro.config.mjs',
  ])('%s', (file) => {
    const text = read(file);
    expect(text).toContain(PLACEHOLDER);
    expect(text).not.toContain('%SITE_URL%');
    expect(text).not.toMatch(/https:\/\/example\.com/);
  });

  it('the prerender strips the CSP it rendered, so it cannot intersect with the real one', () => {
    // Baked into static HTML, the prerender's policy has no CAPTCHA origin; a browser enforcing
    // it alongside the one the page writes from the real config would block the widget.
    expect(read('railhook-ui/scripts/prerender.mjs')).toMatch(/meta\[http-equiv="Content-Security-Policy"\][^\n]*remove\(\)/);
  });
});

describe('the page loads the runtime config before the app', () => {
  const html = read('railhook-ui/index.html');

  it('as a classic script ahead of the module bundle', () => {
    const config = html.search(/<script src="\/config\.js"><\/script>/);
    const app = html.search(/<script type="module"/);
    expect(config, '<script src="/config.js">').toBeGreaterThan(-1);
    expect(app).toBeGreaterThan(-1);
    expect(config).toBeLessThan(app);
  });

  it('with a placeholder for the dev server, so it gets a valid file', () => {
    const window: { __RAILHOOK__?: unknown } = {};
    new Function('window', read('railhook-ui/public/config.js'))(window);
    expect(window.__RAILHOOK__).toEqual(EMPTY);
  });
});

/** Cross-file contract: the deployment files must hand the container these names. */
describe('status page link', () => {
  it('carries an https status page address into the config', () => {
    const { config } = runEntrypoint({ RAILHOOK_STATUS_PAGE_URL: ' https://status.railhook.io ' });
    expect(config).toEqual({ ...EMPTY, statusPageUrl: 'https://status.railhook.io' });
  });

  it('is off by default, and stays off on anything that is not a plain https URL', () => {
    expect(runEntrypoint({}).config?.statusPageUrl).toBe('');
    const { status, js, config, stderr } = runEntrypoint({ RAILHOOK_STATUS_PAGE_URL: 'javascript:alert(1)"};//' });
    expect(status).toBe(0);
    expect(config).toEqual(EMPTY);
    expect(js).not.toContain('alert');
    expect(stderr).toMatch(/RAILHOOK_STATUS_PAGE_URL/);
  });

  it('reaches the container from .env.dist, Compose and the Helm chart', () => {
    expect(read('.env.dist')).toMatch(/^#\s*STATUS_PAGE_URL=$/m);
    expect(read('docker-compose.yml')).toMatch(/^\s+RAILHOOK_STATUS_PAGE_URL: \$\{STATUS_PAGE_URL:-\}$/m);
    expect(read('deploy/helm/railhook/templates/ui-deployment.yaml'))
      .toMatch(/name: RAILHOOK_STATUS_PAGE_URL\s+value: \{\{ \.Values\.ui\.statusPageUrl \| default "" \| quote \}\}/);
  });
});

describe('the settings reach the container', () => {
  const compose = read('docker-compose.yml');
  const ui = compose.slice(compose.indexOf('\n  ui:'), compose.indexOf('\n  caddy:'));
  const deployment = read('deploy/helm/railhook/templates/ui-deployment.yaml');

  it('.env.dist documents the contact domain and the challenge site key', () => {
    const envDist = read('.env.dist');
    expect(envDist).toMatch(/^#\s*WEB_ANALYTICS_TOKEN=$/m);
    expect(envDist).toMatch(/^RAILHOOK_CONTACT_DOMAIN=$/m);
    expect(envDist).toMatch(/^#\s*CAPTCHA_SITE_KEY=$/m);
    expect(envDist).toMatch(/^#\s*CAPTCHA_SCRIPT_URL=/m);
  });

  it('APP_BASE_URL defaults to where Compose publishes the dashboard, not the Vite dev server', () => {
    // It defaulted to localhost:5173, which is `npm run dev`. `make up` publishes the ui container
    // on RAILHOOK_PORT (80), so every verification, reset and invite link a local stack mailed
    // pointed at a port nothing listened on. install.sh writes the real address either way.
    const api = compose.slice(compose.indexOf('\n  api:'), compose.indexOf('\n  worker:'));
    expect(read('.env.dist')).toMatch(/^APP_BASE_URL=http:\/\/localhost$/m);
    expect(api).toMatch(/^\s+APP_BASE_URL: \$\{APP_BASE_URL:-http:\/\/localhost\}$/m);
    expect(ui).toMatch(/\$\{RAILHOOK_PORT:-80\}:5173/);
    expect(read('install.sh')).toMatch(/^APP_BASE_URL=\$\{BASE_URL\}$/m);
  });

  it('Compose passes all five to the ui service', () => {
    expect(ui).toMatch(/^\s+RAILHOOK_CONTACT_DOMAIN: \$\{RAILHOOK_CONTACT_DOMAIN:-\}$/m);
    expect(ui).toMatch(/^\s+RAILHOOK_SITE_URL: \$\{APP_BASE_URL:-\}$/m);
    expect(ui).toMatch(/^\s+RAILHOOK_CAPTCHA_SITE_KEY: \$\{CAPTCHA_SITE_KEY:-\}$/m);
    expect(ui).toMatch(/^\s+RAILHOOK_CAPTCHA_SCRIPT_URL: \$\{CAPTCHA_SCRIPT_URL:-\}$/m);
    expect(ui).toMatch(/^\s+RAILHOOK_WEB_ANALYTICS_TOKEN: \$\{WEB_ANALYTICS_TOKEN:-\}$/m);
  });

  it('the Helm chart passes all five to the UI pod', () => {
    expect(deployment).toMatch(/name: RAILHOOK_CONTACT_DOMAIN\s+value: \{\{ \.Values\.ui\.contactDomain \| default "" \| quote \}\}/);
    expect(deployment).toMatch(/name: RAILHOOK_SITE_URL\s+value: \{\{ include "railhook\.appBaseUrl" \. \| quote \}\}/);
    expect(deployment).toMatch(/name: RAILHOOK_CAPTCHA_SITE_KEY\s+value: \{\{ \.Values\.ui\.captcha\.siteKey \| default "" \| quote \}\}/);
    expect(deployment).toMatch(/name: RAILHOOK_CAPTCHA_SCRIPT_URL\s+value: \{\{ \.Values\.ui\.captcha\.scriptUrl \| default "" \| quote \}\}/);
    expect(deployment).toMatch(/name: RAILHOOK_WEB_ANALYTICS_TOKEN\s+value: \{\{ \.Values\.ui\.webAnalyticsToken \| default "" \| quote \}\}/);
    expect(read('deploy/helm/railhook/values.yaml')).toMatch(/^ {2}contactDomain: ""$/m);
    expect(read('deploy/helm/railhook/values.yaml')).toMatch(/^ {2}webAnalyticsToken: ""$/m);
  });
});
