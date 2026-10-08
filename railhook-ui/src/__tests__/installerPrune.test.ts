// @vitest-environment node
import { afterEach, describe, expect, it } from 'vitest';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdtempSync, readdirSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

function helperSource(): string {
  const installer = readFileSync(join(repoRoot, 'install.sh'), 'utf8');
  const start = installer.indexOf(`<<'HELPER'\n`);
  const end = installer.indexOf('\nHELPER\n', start);
  return installer.slice(start + `<<'HELPER'\n`.length, end + 1);
}

const IMAGES = [
  'ghcr.io/vadymkykalo/railhook-api:3.6.0',
  'ghcr.io/vadymkykalo/railhook-api:3.5.3',
  'ghcr.io/vadymkykalo/railhook-api:3.4.1',
  'ghcr.io/vadymkykalo/railhook-worker:2.20.13',
  'railhook/railhook-ui:3.5.0',
  'ghcr.io/vadymkykalo/railhook-ui:3.6.0',
  'postgres:16-alpine',
  'grafana/grafana:12.1.1',
  'ghcr.io/someone/railhook-api-gateway:1.0.0',
];

const dirs: string[] = [];
afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

function install(dumps: number) {
  const dir = mkdtempSync(join(tmpdir(), 'railhook-prune-'));
  dirs.push(dir);
  const bin = join(dir, 'bin');
  spawnSync('mkdir', ['-p', bin]);
  writeFileSync(
    join(bin, 'docker'),
    [
      '#!/bin/sh',
      'echo "docker $*" >> "$(dirname "$0")/calls"',
      `[ "$1" = images ] && printf '%s\\n' ${IMAGES.map((i) => `'${i}'`).join(' ')}`,
      'exit 0',
      '',
    ].join('\n'),
  );
  chmodSync(join(bin, 'docker'), 0o755);
  writeFileSync(join(dir, 'railhook'), helperSource());
  chmodSync(join(dir, 'railhook'), 0o755);
  writeFileSync(join(dir, '.env'), 'API_IMAGE_TAG=3.6.0\n');
  const day = 86_400;
  for (let i = 0; i < dumps; i++) {
    const file = join(dir, `backup-202609${String(10 + i).padStart(2, '0')}T000000Z.dump`);
    writeFileSync(file, 'dump');
    const t = 1_790_000_000 + i * day;
    utimesSync(file, t, t);
  }
  return { dir, bin };
}

function prune(dir: string, bin: string, args: string[]) {
  const result = spawnSync('bash', [join(dir, 'railhook'), 'prune', ...args], {
    encoding: 'utf8',
    env: { PATH: `${bin}:${process.env.PATH ?? '/usr/bin:/bin'}`, HOME: dir },
  });
  expect(result.status, `${result.stdout}${result.stderr}`).toBe(0);
  const calls = readFileSync(join(bin, 'calls'), 'utf8');
  return calls
    .split('\n')
    .filter((line) => line.startsWith('docker rmi '))
    .map((line) => line.slice('docker rmi '.length));
}

describe('railhook prune', () => {
  it('removes Railhook images except the running tag and the one to roll back to', () => {
    const { dir, bin } = install(0);

    expect(prune(dir, bin, ['3.5.3']).sort()).toEqual([
      'ghcr.io/vadymkykalo/railhook-api:3.4.1',
      'ghcr.io/vadymkykalo/railhook-worker:2.20.13',
    ]);
  });

  it('prunes only the registry DOCKER_REGISTRY names', () => {
    const { dir, bin } = install(0);
    writeFileSync(join(dir, '.env'), 'API_IMAGE_TAG=3.6.0\nDOCKER_REGISTRY=railhook/railhook\n');

    expect(prune(dir, bin, [])).toEqual(['railhook/railhook-ui:3.5.0']);
  });

  it('keeps the five newest upgrade dumps', () => {
    const { dir, bin } = install(8);
    prune(dir, bin, []);

    expect(readdirSync(dir).filter((f) => f.endsWith('.dump')).sort()).toEqual([
      'backup-20260913T000000Z.dump',
      'backup-20260914T000000Z.dump',
      'backup-20260915T000000Z.dump',
      'backup-20260916T000000Z.dump',
      'backup-20260917T000000Z.dump',
    ]);
  });

  it('runs at the end of every upgrade, keeping the version it upgraded from', () => {
    const helper = helperSource();
    const upgrade = helper.slice(helper.indexOf('    upgrade)'), helper.indexOf('    backup)'));
    expect(upgrade).toMatch(/up_one worker[^\n]*\n[\s\S]*prune "\$\{want:-\$from\}" "\$from"/);
  });
});
