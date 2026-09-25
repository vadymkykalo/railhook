import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

/** A literal proxy_pass resolves once; a recreated API meant 32s of 502. */
describe('nginx upstream resolution', () => {
  const conf = read('railhook-ui/nginx.conf');

  it('does not hardcode whose DNS, and does not try to patch itself', () => {
    // 127.0.0.11 doesn't exist under Kubernetes, and the root fs is read-only, hence /tmp.
    expect(conf).toMatch(/^\s*include\s+\/tmp\/railhook-resolver\.conf;/m);
    expect(conf, 'the resolver address must not be hardcoded').not.toMatch(
      /^\s*resolver\s+\d+\.\d+\.\d+\.\d+/m,
    );
  });

  it('the included file is written before nginx starts, from resolv.conf', () => {
    const entrypoint = read('railhook-ui/docker-entrypoint.d/15-resolver.sh');
    expect(entrypoint).toMatch(/\/tmp\/railhook-resolver\.conf/);
    expect(entrypoint).toMatch(/\/etc\/resolv\.conf/);
    expect(entrypoint, 'writing into /etc/nginx fails on a read-only root filesystem')
      .not.toMatch(/sed -i[^\n]*\/etc\/nginx/);
    // Without the copy the include names a missing file and nginx won't start.
    expect(read('railhook-ui/Dockerfile')).toMatch(
      /COPY .*docker-entrypoint\.d\/15-resolver\.sh \/docker-entrypoint\.d\//,
    );
  });

  it('bounds how long a resolved address is kept', () => {
    // Docker publishes a 600s TTL.
    const entrypoint = read('railhook-ui/docker-entrypoint.d/15-resolver.sh');
    const valid = entrypoint.match(/resolver \$\{RESOLVER\} valid=(\d+)s/);
    expect(valid, 'the generated resolver line must set valid=').not.toBeNull();
    expect(Number(valid![1])).toBeLessThanOrEqual(30);
  });

  it('resolves the upstream by a name its own resolver can answer', () => {
    // nginx's resolver applies no search list, so bare `api` is NXDOMAIN under Kubernetes.
    const entrypoint = read('railhook-ui/docker-entrypoint.d/15-resolver.sh');
    expect(entrypoint, 'the search list has to be read').toMatch(/\^search/);
    // Qualified first: libc applies the search list, so probing bare `api` always succeeds.
    const probes = [...entrypoint.matchAll(/getent hosts "([^"]+)"/g)].map((m) => m[1]);
    expect(probes.length, 'a candidate has to be probed through libc').toBeGreaterThan(0);
    expect(probes[0], 'the suffixed name must be probed before the bare one').toContain(
      '${suffix}',
    );
    // Escaped because the heredoc is unquoted.
    expect(entrypoint).toMatch(/set \\\$api_backend/);
    expect(entrypoint).toMatch(/set \\\$api_actuator/);
  });

  it('keeps working defaults if the generated file says nothing about the upstream', () => {
    // After the defaults, or the override is dead code.
    const defaultAt = conf.search(/^\s*set \$api_backend\s/m);
    const includeAt = conf.search(/^\s*include\s+\/tmp\/railhook-resolver\.conf;/m);
    expect(defaultAt, 'nginx fails to start on an undefined variable').toBeGreaterThan(-1);
    expect(includeAt).toBeGreaterThan(defaultAt);
  });

  it('never names the API directly in a proxy_pass', () => {
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

/** container_name blocks the second container a rolling upgrade needs. */
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
    // Docker DNS publishes a container before it is healthy; the alias keeps traffic off it.
    expect(installer).toMatch(/--alias api-warming/);
    expect(installer).toMatch(/--alias api\b/);
    expect(installer).toMatch(/State\.Health\.Status/);
  });

  it('rolls a one-replica host too, by borrowing a second only for the upgrade', () => {
    // One API by default; the roll scales to two only for the swap.
    const roll = installer.slice(installer.indexOf('roll_api() {'));
    const guard = roll.match(/\$\{target:-0\}" (-le|-lt|-eq) ([0-9]+)/);
    expect(guard, 'roll_api still has to decide when there is nothing to roll').not.toBeNull();
    expect(`${guard![1]} ${guard![2]}`, 'one replica must still be rolled').not.toBe('-le 1');
    expect(roll).toMatch(/--scale api=\$\(\(target \+ 1\)\)/);
  });

  it('upgrade replaces the helper before it uses it', () => {
    // The helper never refreshed itself, so its fixes reached a host one deploy late.
    expect(installer).toMatch(/--refresh/);
    const roll = installer.slice(installer.indexOf('upgrade)'));
    expect(roll, 'the new helper has to be the one that runs').toMatch(/exec "\$0"/);
    expect(roll, 'and only once, or it re-execs for ever').toMatch(
      /RAILHOOK_HELPER_REFRESHED/,
    );
    // Read from the helper so renaming it in one place fails.
    const asked = roll.match(/bash -s -- (--[a-z-]+) --dir/);
    expect(asked, 'the helper has to ask for something').not.toBeNull();
    expect(installer, `install.sh must accept ${asked?.[1]}`).toMatch(
      new RegExp(`\\|?\\${asked![1]}[|)]`),
    );
  });

  it('and says so where the operator is looking', () => {
    // The refresh's output used to go to /dev/null, so deploy logs couldn't show a failed reload.
    const roll = installer.slice(installer.indexOf('upgrade)'));
    const refresh = roll.slice(roll.indexOf('bash -s -- --refresh'));
    expect(
      refresh.slice(0, 120),
      'the refresh has to report what it did, not swallow it',
    ).not.toMatch(/>\/dev\/null/);
  });

  it('and a rewritten Caddyfile is actually the one Caddy is serving', () => {
    // A bind-mounted Caddyfile isn't picked up by `up -d`; validate first, and never abort the upgrade.
    expect(installer).toMatch(/caddy validate/);
    expect(installer).toMatch(/caddy reload/);
    const reload = installer.slice(installer.indexOf('reload_caddy() {'));
    expect(reload.slice(0, 1200), 'validate has to come first').toMatch(
      /validate[\s\S]*reload/,
    );
    expect(reload.slice(0, 1200)).toMatch(/ps .*caddy|caddy.*running|-q caddy/);
  });

  it('and the retry actually reaches a host that already exists', () => {
    // Existing hosts never got Caddyfile changes; an install without a domain must not gain one.
    expect(installer).toMatch(/write_caddyfile/);
    const refresh = installer.slice(installer.search(/^\s+refresh\)/m));
    expect(refresh.slice(0, 400)).toMatch(/write_helper/);
    expect(refresh.slice(0, 400)).toMatch(/Caddyfile/);
  });

  it('a UI restart is a slow request, not a 502', () => {
    // The UI's nginx binds a host port and can't be rolled, so Caddy retries refused dials.
    expect(installer).toMatch(/lb_try_duration/);
    expect(installer).toMatch(/lb_try_interval/);
  });

  it('and drains the one it replaces rather than killing it', () => {
    // SIGTERM lets Spring finish in-flight requests; rm would drop them.
    const roll = installer.slice(installer.indexOf('roll_api() {'));
    expect(roll.indexOf('docker stop')).toBeGreaterThan(-1);
    expect(roll.indexOf('docker stop')).toBeLessThan(roll.indexOf('docker rm -f "$old"'));
  });
});
