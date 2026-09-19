import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { EndpointResponse, PageResponse, ProjectResponse } from '../../types/api.types';
import type { SubscriptionResponse } from '../../api/subscriptions.api';

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

const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1',
  projectId: TEST_PROJECT_ID,
  url: 'https://example.com/webhook',
  description: 'Production handler',
  enabled: true,
  rateLimitPerSecond: 25,
  verificationStatus: 'VERIFIED',
  signatureScheme: 'BOTH',
  createdAt: NOW,
  updatedAt: NOW,
};

const SUBSCRIPTION: SubscriptionResponse = {
  id: 'sub-1',
  projectId: TEST_PROJECT_ID,
  endpointId: ENDPOINT.id,
  eventType: 'order.created',
  enabled: true,
  orderingEnabled: false,
  maxAttempts: 3,
  timeoutSeconds: 30,
  retryDelays: '60,300',
  payloadTemplate: null,
  customHeaders: null,
  transformationId: null,
  transformationName: null,
  createdAt: NOW,
  updatedAt: NOW,
};

function emptyDeliveryPage(): PageResponse<never> {
  return {
    content: [], totalElements: 0, totalPages: 0, size: 100, number: 0, first: true, last: true,
  } as unknown as PageResponse<never>;
}

function arrange(endpoint: EndpointResponse = ENDPOINT) {
  vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
  vi.mocked(endpointsApi.list).mockResolvedValue([endpoint]);
  vi.mocked(subscriptionsApi.list).mockResolvedValue([SUBSCRIPTION]);
  vi.mocked(deliveriesApi.listByProject).mockResolvedValue(emptyDeliveryPage());
}

function renderConnections() {
  return renderPage(<ConnectionsPage />, {
    path: '/projects/:projectId/connections',
    initialEntry: `/projects/${TEST_PROJECT_ID}/connections`,
  });
}

async function expandTheRow() {
  const { default: userEvent } = await import('@testing-library/user-event');
  const user = userEvent.setup();
  await screen.findByText(ENDPOINT.url);
  await user.click(screen.getByRole('button', { name: /details for/i }));
  return user;
}

describe('ConnectionsPage — signature scheme', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('still opens the setup flow from "New connection" after the dialog moved out of this page', async () => {
    // The dialog chrome now lives in ConnectionSetupDialog so the dashboard can
    // open the same flow. This guards the extraction, and in particular the
    // `open &&` mount guard: without it the flow keeps the previous attempt's
    // step, endpoint id and secret.
    arrange();
    const user = userEvent.setup();
    renderConnections();

    await user.click(await screen.findByRole('button', { name: /New connection/i }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('New connection')).toBeInTheDocument();
  });

  it('shows the endpoint’s current scheme as the selected option', async () => {
    arrange({ ...ENDPOINT, signatureScheme: 'LEGACY' });
    renderConnections();
    await expandTheRow();

    const group = await screen.findByRole('radiogroup', { name: /signature headers/i });
    expect(group).toBeInTheDocument();
    const legacy = screen.getByRole('radio', { name: 'X-Signature only' });
    expect(legacy).toHaveAttribute('aria-checked', 'true');
  });

  it('has no detectable axe violations with the picker on screen', async () => {
    arrange();
    const { container } = renderConnections();
    await expandTheRow();
    await screen.findByRole('radiogroup', { name: /signature headers/i });
    expect(await axe(container)).toHaveNoViolations();
  });

  it('an endpoint created before the column existed reads as BOTH rather than nothing selected', async () => {
    arrange({ ...ENDPOINT, signatureScheme: undefined });
    renderConnections();
    await expandTheRow();

    const both = await screen.findByRole('radio', { name: 'Both header sets' });
    expect(both).toHaveAttribute('aria-checked', 'true');
  });

  it('choosing a scheme saves it without clearing the rate limit the update also carries', async () => {
    arrange();
    vi.mocked(endpointsApi.update).mockResolvedValue({ ...ENDPOINT, signatureScheme: 'STANDARD' });
    renderConnections();
    const user = await expandTheRow();

    await user.click(await screen.findByRole('radio', { name: 'Standard Webhooks only' }));

    await waitFor(() =>
      expect(endpointsApi.update).toHaveBeenCalledWith(TEST_PROJECT_ID, ENDPOINT.id, {
        url: ENDPOINT.url,
        description: ENDPOINT.description,
        enabled: true,
        rateLimitPerSecond: 25,
        signatureScheme: 'STANDARD',
      })
    );
  });
});

describe('ConnectionsPage — the Standard Webhooks secret', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('offers the whsec_ form alongside the raw secret after a rotation', async () => {
    arrange();
    vi.mocked(endpointsApi.rotateSecret).mockResolvedValue({
      ...ENDPOINT,
      secret: 'a'.repeat(64),
      standardWebhooksSecret: 'whsec_c2VjcmV0LWJ5dGVz',
    });
    renderConnections();
    const user = await expandTheRow();

    await user.click(screen.getByRole('button', { name: /rotate secret/i }));
    const confirm = await screen.findByRole('dialog');
    await user.click(within(confirm).getByRole('button', { name: /rotate secret/i }));

    const fields = await screen.findAllByTestId('signing-secret');
    expect(fields).toHaveLength(2);
    await user.click(screen.getAllByRole('button', { name: /reveal secret/i })[1]);
    expect(screen.getByText('whsec_c2VjcmV0LWJ5dGVz')).toBeInTheDocument();
  });

  it('does not offer it for a LEGACY endpoint, which is sent no Standard Webhooks headers to verify', async () => {
    arrange({ ...ENDPOINT, signatureScheme: 'LEGACY' });
    vi.mocked(endpointsApi.rotateSecret).mockResolvedValue({
      ...ENDPOINT,
      signatureScheme: 'LEGACY',
      secret: 'a'.repeat(64),
      standardWebhooksSecret: 'whsec_c2VjcmV0LWJ5dGVz',
    });
    renderConnections();
    const user = await expandTheRow();

    await user.click(screen.getByRole('button', { name: /rotate secret/i }));
    const confirm = await screen.findByRole('dialog');
    await user.click(within(confirm).getByRole('button', { name: /rotate secret/i }));

    const fields = await screen.findAllByTestId('signing-secret');
    expect(fields).toHaveLength(1);
  });
});
