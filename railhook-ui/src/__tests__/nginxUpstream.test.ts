import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

/**
 * nginx must not hold an address the API has moved off.
 *
 * A name in a literal `proxy_pass` is resolved once, at startup, and kept for the life of the
 * process. Docker gives a recreated container a new address, so nginx went on posting to one
 * that no longer answered — measured at 32 seconds of 502 on an ordinary `up -d api`, of which
 * roughly seven were nginx alone and the rest the JVM starting.
 *
 * Resolving through a variable makes nginx look the name up per request instead, honouring the
 * `valid=` window. Both halves are required and neither is any use without the other, which is
 * why they are asserted together.
 */
describe('nginx upstream resolution', () => {
  const conf = read('railhook-ui/nginx.conf');

  it('resolves rather than caching for ever, and does not hardcode whose DNS', () => {
    // Hardcoding Docker's 127.0.0.11 made every lookup fail under Helm — Kubernetes has
    // no such address, so nginx answered 502 for everything and the chart's smoke test
    // caught it. The address is filled in at container start from /etc/resolv.conf,
    // which is the one place that is right in both.
    expect(conf).toMatch(/^\s*resolver\s+__NGINX_RESOLVER__\b/m);
    expect(conf, 'the resolver address must not be hardcoded').not.toMatch(
      /^\s*resolver\s+\d+\.\d+\.\d+\.\d+/m,
    );
  });

  it('the placeholder is actually substituted before nginx starts', () => {
    const entrypoint = read('railhook-ui/docker-entrypoint.d/15-resolver.sh');
    expect(entrypoint).toMatch(/__NGINX_RESOLVER__/);
    expect(entrypoint).toMatch(/\/etc\/resolv\.conf/);
    // The base image runs /docker-entrypoint.d/*.sh before nginx; if it is not copied
    // in, the placeholder reaches nginx verbatim and it refuses to start at all.
    expect(read('railhook-ui/Dockerfile')).toMatch(
      /COPY .*docker-entrypoint\.d\/15-resolver\.sh \/docker-entrypoint\.d\//,
    );
  });

  it('bounds how long a resolved address is kept', () => {
    // Docker publishes a 600s TTL. Inheriting it would restore the old behaviour with
    // extra steps, so the window is set here rather than taken from the record.
    const valid = conf.match(/^\s*resolver\s+\S+[^\n;]*valid=(\d+)s/m);
    expect(valid, 'resolver must set valid=').not.toBeNull();
    expect(Number(valid![1])).toBeLessThanOrEqual(30);
  });

  it('never names the API directly in a proxy_pass', () => {
    // A literal is what gets cached. The variable is the whole mechanism.
    const literals = [...conf.matchAll(/proxy_pass\s+https?:\/\/(?!\$)([^\s;]+)/g)].map((m) => m[1]);
    expect(literals).toEqual([]);
  });

  it('every API proxy_pass goes through a declared variable', () => {
    const vars = new Set([...conf.matchAll(/proxy_pass\s+https?:\/\/\$([A-Za-z_][A-Za-z0-9_]*)/g)].map((m) => m[1]));
    expect(vars.size).toBeGreaterThan(0);
    for (const name of vars) {
      expect(conf, `$${name} is used but never set`).toMatch(
        new RegExp(`^\\s*set\\s+\\$${name}\\s`, 'm'),
      );
    }
  });
});

/**
 * The API must be able to be replaced rather than restarted.
 *
 * `container_name` pins a service to a single container and Compose refuses to scale it at
 * all — so the only way to put a new API in place was to stop the one that was serving. The
 * rolling path in `railhook upgrade` needs a second container to exist for a moment, and it
 * cannot if the name is fixed.
 */
describe('the API can be rolled', () => {
  const compose = read('docker-compose.yml');
  const installer = read('install.sh');

  it('the api service has no fixed container name', () => {
    const api = compose.slice(compose.indexOf('\n  api:'), compose.indexOf('\n  worker:'));
    expect(api).not.toMatch(/^\s*container_name:/m);
  });

  it('the replica count is a setting, and defaults to what the smallest host holds', () => {
    expect(compose).toMatch(/replicas:\s*\$\{API_REPLICAS:-1\}/);
  });

  it('upgrade warms the replacement out of rotation before it takes traffic', () => {
    // Docker's DNS publishes a container's address the moment it exists and does not
    // withhold it while the healthcheck is still failing — so a replacement that joins
    // under its real name is handed live traffic by nginx while the JVM is still booting.
    // The throwaway alias is what keeps it out until it can answer.
    expect(installer).toMatch(/--alias api-warming/);
    expect(installer).toMatch(/--alias api\b/);
    expect(installer).toMatch(/State\.Health\.Status/);
  });

  it('and drains the one it replaces rather than killing it', () => {
    // docker stop sends SIGTERM, which Spring's graceful shutdown uses to finish what is
    // in flight. Going straight to rm would drop those requests on the floor.
    const roll = installer.slice(installer.indexOf('roll_api() {'));
    expect(roll.indexOf('docker stop')).toBeGreaterThan(-1);
    expect(roll.indexOf('docker stop')).toBeLessThan(roll.indexOf('docker rm -f "$old"'));
  });
});
