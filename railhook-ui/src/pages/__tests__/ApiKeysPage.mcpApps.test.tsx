import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { McpGrantResponse, PageResponse, ProjectResponse } from '../../types/api.types';
import type { ApiKeyResponse } from '../../api/apiKeys.api';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/apiKeys.api', () => ({
  apiKeysApi: { list: vi.fn(), listPaged: vi.fn(), create: vi.fn(), rotate: vi.fn(), revoke: vi.fn() },
}));
vi.mock('../../api/mcpApps.api', () => ({
  mcpAppsApi: { getRequest: vi.fn(), approve: vi.fn(), deny: vi.fn(), listGrants: vi.fn(), revokeGrant: vi.fn() },
}));

import ApiKeysPage from '../ApiKeysPage';
import { projectsApi } from '../../api/projects.api';
import { apiKeysApi } from '../../api/apiKeys.api';
import { mcpAppsApi } from '../../api/mcpApps.api';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

function grant(overrides: Partial<McpGrantResponse> = {}): McpGrantResponse {
  return {
    id: 'grant-1',
    projectId: TEST_PROJECT_ID,
    clientName: 'Claude',
    clientUri: null,
    redirectHost: 'claude.ai',
    scope: 'READ_ONLY',
    approvedByEmail: 'owner@example.com',
    createdAt: new Date().toISOString(),
    lastUsedAt: null,
    ...overrides,
  };
}

const EMPTY_KEYS = { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true } as unknown as PageResponse<ApiKeyResponse>;

function renderApiKeys(auth = {}) {
  return renderPage(<ApiKeysPage />, {
    path: '/projects/:projectId/api-keys',
    initialEntry: `/projects/${TEST_PROJECT_ID}/api-keys`,
    auth,
  });
}

describe('ApiKeysPage — connected AI apps', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(apiKeysApi.listPaged).mockResolvedValue(EMPTY_KEYS);
  });

  it('lists each connected app with its access, host and who approved it', async () => {
    vi.mocked(mcpAppsApi.listGrants).mockResolvedValue([
      grant(),
      grant({ id: 'grant-2', clientName: 'ChatGPT', redirectHost: 'chatgpt.com', scope: 'READ_WRITE' }),
    ]);
    renderApiKeys();

    const list = await screen.findByRole('list', { name: 'Connected AI apps' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText('Claude')).toBeInTheDocument();
    expect(within(items[0]).getByText('Read Only')).toBeInTheDocument();
    expect(within(items[0]).getByText('claude.ai')).toBeInTheDocument();
    expect(within(items[0]).getByText('owner@example.com')).toBeInTheDocument();
    expect(within(items[1]).getByText('Read & Write')).toBeInTheDocument();
    expect(mcpAppsApi.listGrants).toHaveBeenCalledWith(TEST_PROJECT_ID);
  });

  it('disconnects an app after the name is confirmed', async () => {
    const user = userEvent.setup();
    vi.mocked(mcpAppsApi.listGrants).mockResolvedValueOnce([grant()]).mockResolvedValue([]);
    vi.mocked(mcpAppsApi.revokeGrant).mockResolvedValue(undefined);
    renderApiKeys();

    await user.click(await screen.findByRole('button', { name: 'Disconnect Claude' }));
    const dialog = await screen.findByRole('alertdialog').catch(() => screen.getByRole('dialog'));
    await user.type(within(dialog).getByRole('textbox'), 'Claude');
    await user.click(within(dialog).getByRole('button', { name: 'Disconnect' }));

    await waitFor(() => expect(mcpAppsApi.revokeGrant).toHaveBeenCalledWith(TEST_PROJECT_ID, 'grant-1'));
    expect(await screen.findByText('No apps are connected to this project.')).toBeInTheDocument();
  });

  it('tells a project with none how to connect one', async () => {
    vi.mocked(mcpAppsApi.listGrants).mockResolvedValue([]);
    renderApiKeys();

    expect(await screen.findByText('No apps are connected to this project.')).toBeInTheDocument();
    expect(screen.getByText(/\/mcp$/)).toBeInTheDocument();
  });

  it('shows a Viewer the list without a way to disconnect', async () => {
    vi.mocked(mcpAppsApi.listGrants).mockResolvedValue([grant()]);
    renderApiKeys({
      user: {
        user: { id: 'user-2', email: 'viewer@example.com', fullName: 'Viewer', status: 'ACTIVE' },
        organization: { id: 'org-1', name: 'Test Org', createdAt: new Date().toISOString() },
        role: 'VIEWER',
        emailDeliveryEnabled: false,
        hasPassword: true,
        platformAdmin: false,
      },
    });

    expect(await screen.findByText('Claude')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Disconnect Claude' })).not.toBeInTheDocument();
  });
});
