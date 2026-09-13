// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

const SCRIPT = 'railhook-ui/docker-entrypoint.d/20-runtime-config.sh';
const OUT = '/tmp/railhook-config.js';
const SNIPPET = '/etc/nginx/snippets/security-headers.conf';

/**
 * The contact domain is a property of the container, not of the image.
 *
 * It used to be VITE_CONTACT_DOMAIN, inlined into the bundle at build time. The published
 * image is built once and runs both railhook.io and every self-hosted install, so the choice
 * was between an image that offers no mail addresses anywhere — which is what the site had —
 * and one that offers railhook.io's on every stranger's deployment. Neither is right.
 *
 * So the UI container writes `window.__RAILHOOK__` at startup, from RAILHOOK_CONTACT_DOMAIN,
 * nginx serves it as /config.js, and index.html loads it before the app. Each hop is asserted
 * here, because any one missing leaves the page silently without its cards.
 */

/** Runs the entrypoint script as the container would, and evaluates what it wrote. */
function runEntrypoint(env: Record<string, string | undefined>) {
  const dir = mkdtempSync(join(tmpdir(), 'railhook-runtime-config-'));
  const out = join(dir, 'railhook-config.js');
  const childEnv: Record<string, string> = { PATH: process.env.PATH ?? '/usr/bin:/bin', RAILHOOK_RUNTIME_CONFIG_OUT: out };
  for (const [k, v] of Object.entries(env)) if (v !== undefined) childEnv[k] = v;
  const result = spawnSync('sh', [join(repoRoot, SCRIPT)], { env: childEnv, encoding: 'utf8' });
  const js = existsSync(out) ? readFileSync(out, 'utf8') : '';
  rmSync(dir, { recursive: true, force: true });
  const window: { __RAILHOOK__?: { contactDomain?: string } } = {};
  if (js) new Function('window', js)(window);
  return { status: result.status, stderr: result.stderr, js, config: window.__RAILHOOK__ };
}

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
    expect(statSync(join(repoRoot, SCRIPT)).mode & 0o111, 'the script must be executable').not.toBe(0);
  });

  it('carries RAILHOOK_CONTACT_DOMAIN into window.__RAILHOOK__', () => {
    const { status, config } = runEntrypoint({ RAILHOOK_CONTACT_DOMAIN: 'railhook.io' });
    expect(status).toBe(0);
    expect(config).toEqual({ contactDomain: 'railhook.io' });
  });

  it('trims the value', () => {
    expect(runEntrypoint({ RAILHOOK_CONTACT_DOMAIN: '  example.org \t' }).config).toEqual({ contactDomain: 'example.org' });
  });

  it('always writes the file, empty when unset, so /config.js never 404s', () => {
    for (const value of [undefined, '']) {
      const { status, config } = runEntrypoint({ RAILHOOK_CONTACT_DOMAIN: value });
      expect(status).toBe(0);
      expect(config).toEqual({ contactDomain: '' });
    }
  });

  it('refuses anything that is not a hostname, and says so without failing the container', () => {
    for (const value of ['example.org"};alert(1);//', 'exa mple.org', 'mail@example.org', '<script>']) {
      const { status, stderr, js, config } = runEntrypoint({ RAILHOOK_CONTACT_DOMAIN: value });
      expect(status, value).toBe(0);
      expect(config, value).toEqual({ contactDomain: '' });
      expect(js, value).not.toContain('alert');
      expect(stderr, value).toMatch(/RAILHOOK_CONTACT_DOMAIN/);
    }
  });

  it('is copied into the image, where the base entrypoint runs it before nginx', () => {
    const dockerfile = read('railhook-ui/Dockerfile');
    expect(dockerfile).toMatch(/COPY railhook-ui\/docker-entrypoint\.d\/20-runtime-config\.sh \/docker-entrypoint\.d\/20-runtime-config\.sh/);
    expect(dockerfile).toMatch(/chmod \+x[^\n]*\/docker-entrypoint\.d\/20-runtime-config\.sh/);
  });
});

describe('nginx serves the runtime config', () => {
  const conf = read('railhook-ui/nginx.conf');
  const body = locationBody(conf, '= /config.js');

  it('from the file the entrypoint wrote, in an exact-match location', () => {
    // Exact match, so it wins over the static-file regex and over the build-time
    // public/config.js that dist/ also carries.
    expect(body, 'location = /config.js').toBeDefined();
    expect(body).toMatch(new RegExp(`^\\s*alias\\s+${OUT.replace(/\./g, '\\.')};`, 'm'));
  });

  it('never cached, since it changes with the container rather than the release', () => {
    expect(body).toMatch(/add_header\s+Cache-Control\s+"no-cache"\s+always;/);
  });

  it('with the security headers a location that adds its own header would otherwise drop', () => {
    expect(body).toContain(`include ${SNIPPET};`);
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

  it('with a build-time placeholder, so dev and the prerender get a valid file', () => {
    const window: { __RAILHOOK__?: unknown } = {};
    new Function('window', read('railhook-ui/public/config.js'))(window);
    expect(window.__RAILHOOK__).toEqual({ contactDomain: '' });
  });
});

describe('the setting reaches the container', () => {
  it('is documented in .env.dist, empty by default', () => {
    expect(read('.env.dist')).toMatch(/^RAILHOOK_CONTACT_DOMAIN=$/m);
  });

  it('is passed to the ui service by Compose', () => {
    const compose = read('docker-compose.yml');
    const ui = compose.slice(compose.indexOf('\n  ui:'), compose.indexOf('\n  caddy:'));
    expect(ui).toMatch(/^\s+RAILHOOK_CONTACT_DOMAIN: \$\{RAILHOOK_CONTACT_DOMAIN:-\}$/m);
  });

  it('is passed to the UI pod by the Helm chart, empty by default', () => {
    const deployment = read('deploy/helm/railhook/templates/ui-deployment.yaml');
    expect(deployment).toMatch(/name: RAILHOOK_CONTACT_DOMAIN\s+value: \{\{ \.Values\.ui\.contactDomain \| default "" \| quote \}\}/);
    const values = read('deploy/helm/railhook/values.yaml');
    expect(values).toMatch(/^ {2}contactDomain: ""$/m);
  });
});
