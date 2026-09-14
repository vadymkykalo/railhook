// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

/** The `kafka:` service block of docker-compose.yml, up to the next top-level service. */
function kafkaService(): string {
  const compose = read('docker-compose.yml');
  const start = compose.indexOf('\n  kafka:\n');
  const end = compose.indexOf('\n  kafka-init:\n', start);
  expect(start, 'kafka service in docker-compose.yml').toBeGreaterThan(-1);
  expect(end).toBeGreaterThan(start);
  return compose.slice(start, end);
}

function toMiB(size: string): number {
  const m = /^(\d+)([gGmMkK])?/.exec(size);
  expect(m, `a size: ${size}`).not.toBeNull();
  const n = Number(m![1]);
  switch ((m![2] ?? 'b').toLowerCase()) {
    case 'g': return n * 1024;
    case 'm': return n;
    case 'k': return n / 1024;
    default: return n / 1048576;
  }
}

/**
 * The broker's heap is sized for the container it runs in.
 *
 * Left unset, the apache/kafka start script sets `-Xmx1G -Xms1G`: the whole default 1G container
 * limit, committed at start-up, with nothing left for metaspace, 100 threads and network buffers.
 * Production sat at 91% of the limit on a near-idle broker and alerted — one allocation spike from
 * an OOM kill, which takes every delivery down while Kafka restarts. A single broker serving
 * webhook traffic keeps its data in the page cache, not the heap, so half the limit is plenty.
 */
describe('Kafka heap', () => {
  it('is set explicitly, with a default well inside the default container limit', () => {
    const service = kafkaService();

    const heap = /KAFKA_HEAP_OPTS:\s*"?\$\{KAFKA_HEAP_OPTS:-([^}]+)\}"?/.exec(service);
    expect(heap, 'KAFKA_HEAP_OPTS passed to the broker with an overridable default').not.toBeNull();
    const xmx = /-Xmx(\S+)/.exec(heap![1]);
    const xms = /-Xms(\S+)/.exec(heap![1]);
    expect(xmx, 'the default sets -Xmx').not.toBeNull();
    expect(xms, 'the default sets -Xms').not.toBeNull();

    const limit = /memory:\s*"\$\{KAFKA_MEMORY_LIMIT:-([^}]+)\}"/.exec(service);
    expect(limit, 'the kafka memory limit default').not.toBeNull();

    const heapMiB = toMiB(xmx![1]);
    const limitMiB = toMiB(limit![1]);
    expect(heapMiB, `-Xmx ${xmx![1]} leaves room under the ${limit![1]} limit`).toBeLessThanOrEqual(limitMiB * 0.6);
    expect(toMiB(xms![1])).toBeLessThanOrEqual(heapMiB);
  });

  it('is documented next to the memory limit it has to fit', () => {
    const env = read('.env.dist');
    expect(env).toMatch(/^#?\s*KAFKA_HEAP_OPTS=/m);
    expect(env.indexOf('KAFKA_HEAP_OPTS')).toBeGreaterThan(-1);
  });
});
