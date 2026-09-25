import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, PageResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/mcpApps.api', () => ({
  mcpAppsApi: { getRequest: vi.fn(), approve: vi.fn(), deny: vi.fn(), listGrants: vi.fn().mockResolvedValue([]), revokeGrant: vi.fn() },
}));
vi.mock('../../api/apiKeys.api', () => ({
  apiKeysApi: {
    list: vi.fn(),
    listPaged: vi.fn(),
    create: vi.fn(),
    rotate: vi.fn(),
    revoke: vi.fn(),
  },
}));

import ApiKeysPage from '../ApiKeysPage';
import { projectsApi } from '../../api/projects.api';
import { apiKeysApi, type ApiKeyResponse } from '../../api/apiKeys.api';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

function key(overrides: Partial<ApiKeyResponse> = {}): ApiKeyResponse {
  return {
    id: 'key-1',
    projectId: TEST_PROJECT_ID,
    name: 'production ingest',
    keyPrefix: 'hf_live_',
    lastUsedAt: null,
    createdAt: new Date().toISOString(),
    revokedAt: null,
    expiresAt: null,
    scope: 'READ_WRITE',
    rotatedAt: null,
    replacedById: null,
    ...overrides,
  };
}

function page(items: ApiKeyResponse[]): PageResponse<ApiKeyResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0, first: true, last: true } as any;
}

function renderApiKeys() {
  return renderPage(<ApiKeysPage />, {
    path: '/projects/:projectId/api-keys',
    initialEntry: `/projects/${TEST_PROJECT_ID}/api-keys`,
  });
}

describe('ApiKeysPage — rotation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
  });

  it('rotates a key and shows the replacement exactly once', async () => {
    const user = userEvent.setup();
    vi.mocked(apiKeysApi.listPaged).mockResolvedValue(page([key()]));
    vi.mocked(apiKeysApi.rotate).mockResolvedValue(
      key({ id: 'key-2', keyPrefix: 'hf_new_', key: 'hf_new_thereal_plaintext' })
    );

    renderApiKeys();
    await screen.findByText('production ingest');

    await user.click(screen.getByRole('button', { name: /Rotate production ingest/i }));
    await user.click(screen.getByRole('button', { name: /^Rotate$/i }));

    await waitFor(() =>
      expect(apiKeysApi.rotate).toHaveBeenCalledWith(TEST_PROJECT_ID, 'key-1', { gracePeriodHours: 24 })
    );
    expect(await screen.findByText('hf_new_thereal_plaintext')).toBeInTheDocument();
  });

  it('defaults the grace window to a working day rather than to zero', async () => {
    const user = userEvent.setup();
    vi.mocked(apiKeysApi.listPaged).mockResolvedValue(page([key()]));

    renderApiKeys();
    await screen.findByText('production ingest');
    await user.click(screen.getByRole('button', { name: /Rotate production ingest/i }));

    /* The Radix select stays closed in jsdom, so this reads the trigger's label. */
    expect(screen.getByRole('combobox', { name: /Keep the old key working for/i }))
      .toHaveTextContent('24 hours');
  });

  it('shows a key inside its grace window as retiring, not as merely expiring', async () => {
    const tomorrow = new Date(Date.now() + 86_400_000).toISOString();
    vi.mocked(apiKeysApi.listPaged).mockResolvedValue(
      page([key({ rotatedAt: new Date().toISOString(), replacedById: 'key-2', expiresAt: tomorrow })])
    );

    renderApiKeys();

    expect(await screen.findByText('Retiring')).toBeInTheDocument();
    expect(screen.getByText(/Replaced by a newer key/i)).toBeInTheDocument();
  });

  it('does not offer to rotate a key that has already been rotated', async () => {
    vi.mocked(apiKeysApi.listPaged).mockResolvedValue(
      page([key({ rotatedAt: new Date().toISOString(), replacedById: 'key-2' })])
    );

    renderApiKeys();
    await screen.findByText('production ingest');

    expect(screen.queryByRole('button', { name: /Rotate production ingest/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Revoke production ingest/i })).toBeInTheDocument();
  });
});
