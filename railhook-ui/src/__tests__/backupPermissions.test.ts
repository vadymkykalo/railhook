// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

/** Dumps used to be -rw-r--r-- under the default umask. */
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
