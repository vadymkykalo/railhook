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

  it('does not hardcode whose DNS, and does not try to patch itself', () => {
    // Two ways this went wrong. Hardcoding Docker's 127.0.0.11 made every lookup fail
    // under Helm, because Kubernetes has no such address. Substituting it into this file
    // at startup then failed too: the chart runs the pod with a read-only root
    // filesystem, so `sed -i` returns "Permission denied" and nginx starts on a config it
    // cannot parse. The address is written to /tmp and included from there.
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
    // The base image runs /docker-entrypoint.d/*.sh before nginx; without the copy the
    // include names a file that does not exist and nginx refuses to start.
    expect(read('railhook-ui/Dockerfile')).toMatch(
      /COPY .*docker-entrypoint\.d\/15-resolver\.sh \/docker-entrypoint\.d\//,
    );
  });

  it('bounds how long a resolved address is kept', () => {
    // Docker publishes a 600s TTL. Inheriting it would restore the cached-forever
    // behaviour with extra steps, so the window is set rather than taken from the record.
    const entrypoint = read('railhook-ui/docker-entrypoint.d/15-resolver.sh');
    const valid = entrypoint.match(/resolver \$\{RESOLVER\} valid=(\d+)s/);
    expect(valid, 'the generated resolver line must set valid=').not.toBeNull();
    expect(Number(valid![1])).toBeLessThanOrEqual(30);
  });

  it('resolves the upstream by a name its own resolver can answer', () => {
    // nginx's resolver speaks DNS itself, and a raw query carries no search list. So the
    // bare `api` that Docker's embedded DNS answers is NXDOMAIN under Kubernetes, where
    // the name is only reachable as api.<namespace>.svc.cluster.local — and the chart's
    // UI served 502 for every proxied path while nginx itself was perfectly healthy.
    // A literal proxy_pass did not have this problem, because it resolves once through
    // libc, which does apply `search`. The entrypoint has to close that gap.
    const entrypoint = read('railhook-ui/docker-entrypoint.d/15-resolver.sh');
    expect(entrypoint, 'the search list has to be read').toMatch(/\^search/);
    // And the qualified candidate has to be tried FIRST. Probing the bare name and only
    // falling back to a suffix is the shape that looks right and does nothing: libc
    // applies the search list itself, so `getent hosts api` succeeds inside the pod and
    // the bare name — the one nginx cannot resolve — is what gets written.
    const probes = [...entrypoint.matchAll(/getent hosts "([^"]+)"/g)].map((m) => m[1]);
    expect(probes.length, 'a candidate has to be probed through libc').toBeGreaterThan(0);
    expect(probes[0], 'the suffixed name must be probed before the bare one').toContain(
      '${suffix}',
    );
    // And the result has to reach nginx, which means overriding the defaults below.
    // Backslash-escaped in the script: the heredoc is unquoted so that ${API_HOST}
    // expands, which means nginx's own $ has to survive the shell.
    expect(entrypoint).toMatch(/set \\\$api_backend/);
    expect(entrypoint).toMatch(/set \\\$api_actuator/);
  });

  it('keeps working defaults if the generated file says nothing about the upstream', () => {
    // The include has to come after them, or the defaults win and the override is dead
    // code — which is exactly how this would regress.
    const defaultAt = conf.search(/^\s*set \$api_backend\s/m);
    const includeAt = conf.search(/^\s*include\s+\/tmp\/railhook-resolver\.conf;/m);
    expect(defaultAt, 'nginx fails to start on an undefined variable').toBeGreaterThan(-1);
    expect(includeAt).toBeGreaterThan(defaultAt);
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

  it('rolls a one-replica host too, by borrowing a second only for the upgrade', () => {
    // Running two API containers around the clock to buy a seamless upgrade is a bad
    // trade on the box this ships for — the second one costs memory every hour of the
    // day to save thirty seconds a month. So one is the default, and the roll scales to
    // two for the length of the swap and back down again.
    //
    // The regression this guards is the early return: `-le 1` sent exactly the default
    // installation down the restart-in-place path, which is the downtime the roll exists
    // to remove. Only an API that is not running at all has nothing to roll.
    const roll = installer.slice(installer.indexOf('roll_api() {'));
    const guard = roll.match(/\$\{target:-0\}" (-le|-lt|-eq) ([0-9]+)/);
    expect(guard, 'roll_api still has to decide when there is nothing to roll').not.toBeNull();
    expect(`${guard![1]} ${guard![2]}`, 'one replica must still be rolled').not.toBe('-le 1');
    // And the scale-up has to be one more than whatever is there, not a fixed 2.
    expect(roll).toMatch(/--scale api=\$\(\(target \+ 1\)\)/);
  });

  it('upgrade replaces the helper before it uses it', () => {
    // The helper is written once, at install time, and `upgrade` refreshed
    // docker-compose.yml but never itself. So a release that changed the helper —
    // this rolling path, for one — reached an existing host only on the deploy
    // *after* the one that shipped it, and the deploy that was supposed to prove the
    // fix restarted the API in place instead. Measured on the 2.16.2 deploy, which
    // logged "Container railhook-api-9 Recreated" with the roll already released.
    //
    // So it fetches its own replacement first and re-execs, rather than editing the
    // file bash is still reading line by line.
    expect(installer).toMatch(/--write-helper/);
    const roll = installer.slice(installer.indexOf('upgrade)'));
    expect(roll, 'the new helper has to be the one that runs').toMatch(/exec "\$0"/);
    expect(roll, 'and only once, or it re-execs for ever').toMatch(
      /RAILHOOK_HELPER_REFRESHED/,
    );
    // --write-helper has to exist on the other side, or the fetch is a no-op that
    // reports success.
    expect(installer).toMatch(/ACTION="write-helper"/);
    expect(installer).toMatch(/write-helper\)/);
  });

  it('a UI restart is a slow request, not a 502', () => {
    // Caddy is the front door and proxies everything — the dashboard, /hook, /ingress
    // — to one upstream, the UI's nginx. That container cannot be rolled the way the
    // API is, because it publishes a host port and two replicas cannot bind it. So
    // the gap is closed at the proxy instead: Caddy retries a refused dial for a few
    // seconds rather than answering 502 immediately. Nothing has been sent when a
    // dial fails, so the retry is safe for any method.
    expect(installer).toMatch(/lb_try_duration/);
    expect(installer).toMatch(/lb_try_interval/);
  });

  it('and drains the one it replaces rather than killing it', () => {
    // docker stop sends SIGTERM, which Spring's graceful shutdown uses to finish what is
    // in flight. Going straight to rm would drop those requests on the floor.
    const roll = installer.slice(installer.indexOf('roll_api() {'));
    expect(roll.indexOf('docker stop')).toBeGreaterThan(-1);
    expect(roll.indexOf('docker stop')).toBeLessThan(roll.indexOf('docker rm -f "$old"'));
  });
});
