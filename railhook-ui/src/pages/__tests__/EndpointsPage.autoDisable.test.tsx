import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, EndpointResponse, PageResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: {
    list: vi.fn(),
    listPaged: vi.fn(),
    create: vi.fn(),
    delete: vi.fn(),
    update: vi.fn(),
    enable: vi.fn(),
    rotateSecret: vi.fn(),
    test: vi.fn(),
    verify: vi.fn(),
    skipVerification: vi.fn(),
  },
}));

import EndpointsPage from '../EndpointsPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';

const NOW = new Date().toISOString();
const THREE_DAYS_AGO = new Date(Date.now() - 3 * 24 * 3600 * 1000).toISOString();

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: NOW,
  updatedAt: NOW,
};

/** Turned off by its owner: a configuration state, and nothing to explain. */
const MANUALLY_OFF: EndpointResponse = {
  id: 'endpoint-off',
  projectId: TEST_PROJECT_ID,
  url: 'https://example.com/paused',
  enabled: false,
  createdAt: NOW,
  updatedAt: NOW,
};

/** Turned off by Railhook, which the owner did not ask for and has to be told about. */
const AUTO_DISABLED: EndpointResponse = {
  ...MANUALLY_OFF,
  id: 'endpoint-dead',
  url: 'https://example.com/dead',
  failingSince: THREE_DAYS_AGO,
  consecutiveFailures: 412,
  autoDisabledAt: NOW,
  autoDisabledReason: 'No delivery has succeeded for more than 72 hours.',
};

function page<T>(items: T[]): PageResponse<T> {
  return {
    content: items, totalElements: items.length, totalPages: 1,
    size: 20, number: 0, first: true, last: true,
  } as any;
}

function render() {
  return renderPage(<EndpointsPage />, {
    path: '/projects/:projectId/endpoints',
    initialEntry: `/projects/${TEST_PROJECT_ID}/endpoints`,
  });
}

/**
 * An auto-disable is the one thing on this page the owner did not do themselves, so the two
 * states it could be confused with — on, and switched off on purpose — are what these assert
 * against.
 */
describe('EndpointsPage — an endpoint Railhook turned off', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
  });

  it('says it was disabled automatically, and since when', async () => {
    vi.mocked(endpointsApi.listPaged).mockResolvedValue(page([AUTO_DISABLED]));

    render();

    expect(await screen.findByText('Auto-disabled')).toBeInTheDocument();
    expect(screen.getByText(/Railhook stopped sending/i)).toBeInTheDocument();
  });

  it('an endpoint its owner switched off reads as plainly disabled, with nothing to explain', async () => {
    vi.mocked(endpointsApi.listPaged).mockResolvedValue(page([MANUALLY_OFF]));

    render();

    expect(await screen.findByText('Disabled')).toBeInTheDocument();
    expect(screen.queryByText('Auto-disabled')).not.toBeInTheDocument();
    expect(screen.queryByText(/Railhook stopped sending/i)).not.toBeInTheDocument();
  });

  it('re-enabling goes through the enable call, not a rebuilt update', async () => {
    // The update request requires a URL, so toggling through it means resending the endpoint
    // from whatever fields this page happens to render — which is how allowedSourceIps and
    // signatureScheme used to be silently dropped by a click on the power button.
    vi.mocked(endpointsApi.listPaged).mockResolvedValue(page([AUTO_DISABLED]));
    vi.mocked(endpointsApi.enable).mockResolvedValue({ ...AUTO_DISABLED, enabled: true });

    render();
    await screen.findByText('Auto-disabled');

    await userEvent.click(screen.getByRole('button', { name: 'Enable' }));
    await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));

    await waitFor(() => {
      expect(endpointsApi.enable).toHaveBeenCalledWith(TEST_PROJECT_ID, AUTO_DISABLED.id);
    });
    expect(endpointsApi.update).not.toHaveBeenCalled();
  });
});
