// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

/**
 * URLs the product hands to people are public origins, never names from inside the stack.
 *
 * Production showed a test endpoint as `http://api:8080/hook/x52thd04`: the Compose default for
 * TEST_ENDPOINT_BASE_URL was the API's container name, which resolves only on the Docker network,
 * so the URL someone copied into curl or a provider dashboard could never receive a request.
 */
describe('public URLs follow the public origin', () => {
  const compose = read('docker-compose.yml');
  const envDist = read('.env.dist');
  const configmap = read('deploy/helm/railhook/templates/configmap.yaml');

  const INTERNAL = /https?:\/\/(api|worker|ui|postgres|redis|kafka)(:\d+)?\b/;

  it.each(['TEST_ENDPOINT_BASE_URL', 'WEBHOOK_INGRESS_BASE_URL'])(
    'Compose defaults %s to APP_BASE_URL, not to a container name',
    (name) => {
      const line = compose.split('\n').find((l) => l.trim().startsWith(`${name}:`));
      expect(line, `${name} in docker-compose.yml`).toBeDefined();
      expect(line).toContain('${APP_BASE_URL:-');
      expect(line).not.toMatch(INTERNAL);
    },
  );

  it('.env.dist does not set an internal test endpoint origin', () => {
    const active = envDist.split('\n').filter((l) => /^TEST_ENDPOINT_BASE_URL=/.test(l));
    for (const l of active) expect(l).not.toMatch(INTERNAL);
  });

  it('the Helm chart passes the public origin to the API for test endpoints', () => {
    expect(configmap).toMatch(/^\s+TEST_ENDPOINT_BASE_URL: \{\{ include "railhook\.appBaseUrl" \. /m);
  });
});
