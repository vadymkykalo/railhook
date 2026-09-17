import { describe, it, expect, vi } from 'vitest';

vi.mock('../http', () => ({ http: { get: vi.fn().mockResolvedValue({}) } }));

import { dlqApi } from '../dlq.api';
import { http } from '../http';

describe('dlqApi.list', () => {
  it('sends only the parameters the DLQ endpoint accepts', async () => {
    await dlqApi.list('project-1', 0, 20, {
      endpointId: 'endpoint-1', search: 'x', dateFrom: '2026-01-01', dateTo: '2026-01-02',
    } as Parameters<typeof dlqApi.list>[3]);
    const url = String(vi.mocked(http.get).mock.calls[0][0]);
    expect(url).toContain('endpointId=endpoint-1');
    expect(url).not.toMatch(/search|dateFrom|dateTo/);
  });
});
