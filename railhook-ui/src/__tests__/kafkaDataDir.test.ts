// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const compose = readFileSync(join(repoRoot, 'docker-compose.yml'), 'utf8');

function kafkaService(): string {
  const start = compose.indexOf('\n  kafka:\n');
  const end = compose.indexOf('\n  kafka-init:\n', start);
  expect(start, 'kafka service in docker-compose.yml').toBeGreaterThan(-1);
  expect(end).toBeGreaterThan(start);
  return compose.slice(start, end);
}

/** The image writes /tmp/kafka-logs unless told, so recreating the container lost every topic. */
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
