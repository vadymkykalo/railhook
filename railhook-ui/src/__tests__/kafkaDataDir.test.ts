// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const compose = readFileSync(join(repoRoot, 'docker-compose.yml'), 'utf8');

/** The `kafka:` service block of docker-compose.yml, up to the next top-level service. */
function kafkaService(): string {
  const start = compose.indexOf('\n  kafka:\n');
  const end = compose.indexOf('\n  kafka-init:\n', start);
  expect(start, 'kafka service in docker-compose.yml').toBeGreaterThan(-1);
  expect(end).toBeGreaterThan(start);
  return compose.slice(start, end);
}

/**
 * Where the broker keeps its log.
 *
 * The service mounts `kafka_data` at /var/lib/kafka/data, but the image writes to
 * /tmp/kafka-logs unless told otherwise — inside the container, not on the volume. Recreating
 * the container, which any change to its environment does, then threw away every topic, consumer
 * offset and unread message. Found on production: after the heap change was deployed, the DLQ
 * topics were gone and the worker logged UnknownTopicOrPartitionException every minute.
 */
describe('Kafka in docker-compose.yml', () => {
  it('writes its log to the directory the data volume is mounted on', () => {
    const service = kafkaService();
    const mount = /-\s*kafka_data:(\S+)/.exec(service);
    expect(mount, 'kafka_data volume mount').not.toBeNull();
    const logDirs = /KAFKA_LOG_DIRS:\s*(\S+)/.exec(service);
    expect(logDirs, 'KAFKA_LOG_DIRS on the kafka service').not.toBeNull();
    expect(logDirs![1]).toBe(mount![1]);
  });
});
