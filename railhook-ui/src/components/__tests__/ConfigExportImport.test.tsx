import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import type { EndpointResponse } from '../../types/api.types';

vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn(), create: vi.fn() },
}));
vi.mock('../../api/subscriptions.api', () => ({
  subscriptionsApi: { list: vi.fn(), create: vi.fn() },
}));

import ConfigExportImport from '../ConfigExportImport';
import { endpointsApi } from '../../api/endpoints.api';
import { subscriptionsApi } from '../../api/subscriptions.api';

const now = new Date().toISOString();
const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1', projectId: 'project-1', url: 'https://example.com/hook', enabled: true,
  signatureScheme: 'STANDARD', createdAt: now, updatedAt: now,
};

function importFile(config: unknown) {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  const file = new File([JSON.stringify(config)], 'config.json', { type: 'application/json' });
  fireEvent.change(input, { target: { files: [file] } });
}

describe('ConfigExportImport', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(subscriptionsApi.list).mockResolvedValue([]);
  });

  it('exports each endpoint with its signature scheme', async () => {
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
    let exported: Blob | undefined;
    URL.createObjectURL = vi.fn((b: Blob) => { exported = b; return 'blob:x'; }) as typeof URL.createObjectURL;
    URL.revokeObjectURL = vi.fn();
    render(<ConfigExportImport projectId="project-1" projectName="Test" />);

    fireEvent.click(screen.getByRole('button', { name: /export config/i }));

    await waitFor(() => expect(exported).toBeDefined());
    const config = JSON.parse(await exported!.text());
    expect(config.endpoints[0].signatureScheme).toBe('STANDARD');
  });

  it('creates imported endpoints with their signature scheme and shows each new secret once', async () => {
    vi.mocked(endpointsApi.list).mockResolvedValue([]);
    vi.mocked(endpointsApi.create).mockResolvedValue({ ...ENDPOINT, secret: 'generated-secret-value' });
    render(<ConfigExportImport projectId="project-1" projectName="Test" />);

    importFile({
      version: 1, exportedAt: now, projectName: 'Test', subscriptions: [],
      endpoints: [{ url: ENDPOINT.url, enabled: true, signatureScheme: 'STANDARD' }],
    });

    await waitFor(() => expect(endpointsApi.create).toHaveBeenCalledTimes(1));
    expect(vi.mocked(endpointsApi.create).mock.calls[0][1]).toMatchObject({ signatureScheme: 'STANDARD' });

    const secret = await screen.findByTestId('signing-secret');
    expect(screen.getByText(ENDPOINT.url)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /reveal secret/i }));
    expect(secret).toHaveTextContent('generated-secret-value');
  });
});
