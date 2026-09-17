import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../http', () => ({ http: { getBlob: vi.fn() } }));

import { http } from '../http';
import { auditLogApi } from '../auditLog.api';
import { organizationsApi } from '../organizations.api';
import { resolveErrorMessage } from '../../lib/toast';

/** What axios hands back for a failed `responseType: 'blob'` request: the JSON error body, as a Blob. */
function blobBodiedError(status: number, body: unknown) {
  return {
    response: { status, data: new Blob([JSON.stringify(body)], { type: 'application/json' }) },
  };
}

describe('file exports report the server message when they fail', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('audit log CSV export', async () => {
    vi.mocked(http.getBlob).mockRejectedValue(blobBodiedError(400, { message: 'Date range exceeds 90 days' }));

    const err = await auditLogApi.exportCsv().catch((e: unknown) => e);

    expect(resolveErrorMessage(err, 'auditLog.exportFailed')).toBe('Date range exceeds 90 days');
  });

  it('organization data export', async () => {
    vi.mocked(http.getBlob).mockRejectedValue(blobBodiedError(403, { message: 'Only the owner can export' }));

    const err = await organizationsApi.exportData('org-1').catch((e: unknown) => e);

    expect(resolveErrorMessage(err, 'org.exportFailed')).toBe('Only the owner can export');
  });

  it('leaves a body that is not JSON as it was', async () => {
    const original = { response: { status: 502, data: new Blob(['<html>bad gateway</html>']) } };
    vi.mocked(http.getBlob).mockRejectedValue(original);

    const err = await auditLogApi.exportCsv().catch((e: unknown) => e);

    expect(err).toBe(original);
    expect(resolveErrorMessage(err, 'toast.errors.server')).not.toContain('html');
  });
});
