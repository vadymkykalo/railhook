import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { EndpointResponse, ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { create: vi.fn(), update: vi.fn(), test: vi.fn() },
}));
vi.mock('../../api/subscriptions.api', () => ({
  subscriptionsApi: { create: vi.fn() },
}));
vi.mock('../../api/schemas.api', () => ({
  schemasApi: { listEventTypes: vi.fn() },
}));

import ConnectionSetupPage from '../ConnectionSetupPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { schemasApi } from '../../api/schemas.api';

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

const CREATED: EndpointResponse = {
  id: 'endpoint-1',
  projectId: TEST_PROJECT_ID,
  url: 'https://api.acme.io/webhooks',
  enabled: true,
  signatureScheme: 'BOTH',
  secret: 'a'.repeat(64),
  standardWebhooksSecret: 'whsec_c2VjcmV0LWJ5dGVz',
  createdAt: NOW,
  updatedAt: NOW,
};

/** Walks the wizard's first step, which is what puts a secret on the screen. */
async function createTheEndpoint(created: EndpointResponse = CREATED) {
  const { default: userEvent } = await import('@testing-library/user-event');
  const user = userEvent.setup();
  vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
  vi.mocked(schemasApi.listEventTypes).mockResolvedValue([]);
  vi.mocked(endpointsApi.create).mockResolvedValue(created);

  renderPage(<ConnectionSetupPage />, {
    path: '/projects/:projectId/connections/new',
    initialEntry: `/projects/${TEST_PROJECT_ID}/connections/new`,
  });

  await user.type(await screen.findByLabelText(/endpoint url/i), created.url);
  await user.click(screen.getByRole('button', { name: /create endpoint/i }));
  return user;
}

describe('ConnectionSetupPage — the secret step', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('offers the whsec_ secret beside the raw one, because a Standard Webhooks library needs that form', async () => {
    const user = await createTheEndpoint();

    const fields = await screen.findAllByTestId('signing-secret');
    expect(fields).toHaveLength(2);

    await user.click(screen.getAllByRole('button', { name: /reveal secret/i })[1]);
    expect(screen.getByText('whsec_c2VjcmV0LWJ5dGVz')).toBeInTheDocument();
  });

  it('narrowing the scheme to X-Signature saves it and withdraws the whsec_ secret it no longer applies to', async () => {
    const user = await createTheEndpoint();
    vi.mocked(endpointsApi.update).mockResolvedValue({ ...CREATED, signatureScheme: 'LEGACY' });

    await user.click(await screen.findByRole('radio', { name: 'X-Signature only' }));

    await waitFor(() =>
      expect(endpointsApi.update).toHaveBeenCalledWith(
        TEST_PROJECT_ID,
        CREATED.id,
        expect.objectContaining({ url: CREATED.url, signatureScheme: 'LEGACY' })
      )
    );
    await waitFor(() => expect(screen.getAllByTestId('signing-secret')).toHaveLength(1));
  });

  it('a failed save puts the option back, so the picker never shows a scheme the endpoint does not have', async () => {
    const user = await createTheEndpoint();
    vi.mocked(endpointsApi.update).mockRejectedValue({ response: { status: 500 }, message: 'boom' });

    await user.click(await screen.findByRole('radio', { name: 'X-Signature only' }));

    await waitFor(() =>
      expect(screen.getByRole('radio', { name: 'Both header sets' })).toHaveAttribute('aria-checked', 'true')
    );
  });
});
