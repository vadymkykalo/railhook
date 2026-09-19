import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { EndpointResponse, PageResponse, ProjectResponse } from '../../types/api.types';

vi.mock('../../lib/toast', () => ({
  showApiError: vi.fn(),
  showError: vi.fn(),
  showSuccess: vi.fn(),
}));
vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: {
    list: vi.fn(),
    listPaged: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    rotateSecret: vi.fn(),
    test: vi.fn(),
    verify: vi.fn(),
    skipVerification: vi.fn(),
  },
}));
vi.mock('../../api/subscriptions.api', () => ({
  subscriptionsApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));
vi.mock('../../api/deliveries.api', () => ({
  deliveriesApi: { listByProject: vi.fn() },
}));

import ConnectionsPage from '../ConnectionsPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { subscriptionsApi } from '../../api/subscriptions.api';
import { deliveriesApi } from '../../api/deliveries.api';
import { showError } from '../../lib/toast';

const NOW = new Date().toISOString();

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: NOW,
  updatedAt: NOW,
};

const TUNNEL_ENDPOINT: EndpointResponse = {
  id: 'endpoint-1',
  projectId: TEST_PROJECT_ID,
  url: 'https://railhook.io/tunnel/tun-abc',
  enabled: true,
  verificationStatus: 'PENDING',
  createdAt: NOW,
  updatedAt: NOW,
};

/*
 * A tunnel with no `railhook tunnel` client answers 503, and the toast used to print that raw:
 * "Verification failed: Verification request failed: 503 Service Unavailable from POST …". The
 * server now names the cause, and the page says what to do about it in the reader's language.
 */
describe('ConnectionsPage — verifying an endpoint', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(endpointsApi.list).mockResolvedValue([TUNNEL_ENDPOINT]);
    vi.mocked(subscriptionsApi.list).mockResolvedValue([]);
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue({
      content: [], totalElements: 0, totalPages: 0, size: 100, number: 0, first: true, last: true,
    } as unknown as PageResponse<never>);
  });

  async function pressVerify() {
    const user = userEvent.setup();
    await screen.findByText(TUNNEL_ENDPOINT.url);
    await user.click(screen.getByRole('button', { name: /verify endpoint/i }));
  }

  it('an offline tunnel is explained, with the command that brings it up', async () => {
    vi.mocked(endpointsApi.verify).mockResolvedValue({
      success: false,
      message: 'The tunnel is not connected.',
      status: 'FAILED',
      reason: 'TUNNEL_OFFLINE',
    });

    renderPage(<ConnectionsPage />, {
      path: '/projects/:projectId/connections',
      initialEntry: `/projects/${TEST_PROJECT_ID}/connections`,
    });
    await pressVerify();

    await vi.waitFor(() => expect(showError).toHaveBeenCalledTimes(1));
    const shown = vi.mocked(showError).mock.calls[0][0];
    expect(shown).toMatch(/railhook tunnel/);
    expect(shown).not.toMatch(/503/);
  });

  it('any other failure still shows what the server said', async () => {
    vi.mocked(endpointsApi.verify).mockResolvedValue({
      success: false,
      message: 'Challenge token not found in response',
      status: 'FAILED',
    });

    renderPage(<ConnectionsPage />, {
      path: '/projects/:projectId/connections',
      initialEntry: `/projects/${TEST_PROJECT_ID}/connections`,
    });
    await pressVerify();

    await vi.waitFor(() => expect(showError).toHaveBeenCalledTimes(1));
    expect(vi.mocked(showError).mock.calls[0][0]).toMatch(/Challenge token not found/);
  });
});
