// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

/**
 * Database dumps are written readable by their owner only.
 *
 * Production had every dump at -rw-r--r--: the scheduled backup and `./railhook backup` both wrote
 * with the default umask, so any account or process on the host could read a full copy of the
 * database — account emails, API key hashes, payloads — and the encrypted columns sit beside a
 * .env that is one misconfiguration away from being readable too.
 */
describe('backups are owner-only', () => {
  it('the scheduled backup script sets umask 077 before writing a dump', () => {
    const script = read('deploy/scripts/db-backup.sh');
    const umask = script.search(/^umask 077$/m);
    expect(umask, 'umask 077 in db-backup.sh').toBeGreaterThan(-1);
    expect(umask).toBeLessThan(script.indexOf('OUT_FILE='));
  });

  it('the helper backup command sets umask 077 before redirecting pg_dump into a file', () => {
    const install = read('install.sh');
    const backup = install.slice(install.indexOf('    backup)'), install.indexOf('    settings) apply_settings ;;'));
    const umask = backup.search(/umask 077/);
    expect(umask, 'umask 077 in the helper backup case').toBeGreaterThan(-1);
    expect(umask).toBeLessThan(backup.indexOf('pg_dump'));
  });
});
