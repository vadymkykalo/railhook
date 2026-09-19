import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { Route, Routes, useLocation } from 'react-router-dom';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type {
  EndpointResponse, IncomingDestinationResponse, IncomingSourceResponse, PageResponse, ProjectResponse,
} from '../../types/api.types';
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
vi.mock('../../api/incomingSources.api', () => ({
  incomingSourcesApi: { list: vi.fn() },
}));
vi.mock('../../api/incomingDestinations.api', () => ({
  incomingDestinationsApi: { list: vi.fn() },
}));

import ConnectionsPage from '../ConnectionsPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { subscriptionsApi } from '../../api/subscriptions.api';
import { deliveriesApi } from '../../api/deliveries.api';
import { incomingSourcesApi } from '../../api/incomingSources.api';
import { incomingDestinationsApi } from '../../api/incomingDestinations.api';

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

const SOURCE = {
  id: 'source-1', projectId: TEST_PROJECT_ID, name: 'Stripe payments', slug: 'stripe', providerType: 'STRIPE',
  status: 'ACTIVE', ingressPathToken: 'tok', ingressUrl: 'https://in.example.com/tok', verificationMode: 'PROVIDER',
  hmacSecretConfigured: true, createdAt: NOW, updatedAt: NOW,
} as IncomingSourceResponse;

const DESTINATION = {
  id: 'dest-1', incomingSourceId: SOURCE.id, url: 'https://billing.internal/stripe', authType: 'NONE',
  authConfigured: false, enabled: true, maxAttempts: 5, timeoutSeconds: 30, retryDelays: '60,300',
  createdAt: NOW, updatedAt: NOW,
} as IncomingDestinationResponse;

function page<T>(content: T[]): PageResponse<T> {
  return {
    content, totalElements: content.length, totalPages: 1, size: 100, number: 0, first: true, last: true,
  } as unknown as PageResponse<T>;
}

function arrange({ sources = [] as IncomingSourceResponse[] } = {}) {
  vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
  vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
  vi.mocked(subscriptionsApi.list).mockResolvedValue([SUBSCRIPTION]);
  vi.mocked(deliveriesApi.listByProject).mockResolvedValue(page([]));
  vi.mocked(incomingSourcesApi.list).mockResolvedValue(page(sources));
  vi.mocked(incomingDestinationsApi.list).mockResolvedValue(page([DESTINATION]));
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

function renderConnections() {
  return renderPage(
    <Routes>
      <Route path="/admin/projects/:projectId/connections" element={<ConnectionsPage />} />
      <Route path="*" element={<Where />} />
    </Routes>,
    { path: '*', initialEntry: `/admin/projects/${TEST_PROJECT_ID}/connections` },
  );
}

/**
 * Connections is the one list of where events go. The endpoint and subscription tables left the
 * tab strip, so this page has to reach them, and a row has to open the endpoint it stands for.
 */
describe('ConnectionsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('still opens the setup flow from "New connection"', async () => {
    // The `open &&` mount guard in ConnectionSetupDialog is what this holds: without it the flow
    // keeps the previous attempt's step, endpoint id and secret.
    arrange();
    const user = userEvent.setup();
    renderConnections();

    await user.click(await screen.findByRole('button', { name: /New connection/i }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('New connection')).toBeInTheDocument();
  });

  it('opens the endpoint\'s page from anywhere on its row', async () => {
    arrange();
    renderConnections();

    const row = (await screen.findByText(ENDPOINT.url)).closest('tr')!;
    fireEvent.click(within(row).getByText('order.created'));

    expect(await screen.findByTestId('where'))
      .toHaveTextContent(`/admin/projects/${TEST_PROJECT_ID}/endpoints/${ENDPOINT.id}`);
  });

  it('reaches the raw endpoint and subscription tables that left the tab strip', async () => {
    arrange();
    renderConnections();
    await screen.findByText(ENDPOINT.url);

    expect(screen.getByRole('link', { name: 'All endpoints' }))
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/endpoints`);
    expect(screen.getByRole('link', { name: 'All subscriptions' }))
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/subscriptions`);
  });

  it('shows the incoming half: each source and the destinations it forwards to', async () => {
    arrange({ sources: [SOURCE] });
    renderConnections();

    const incoming = await screen.findByRole('region', { name: 'Incoming' });
    expect(await within(incoming).findByText(DESTINATION.url)).toBeInTheDocument();
    expect(within(incoming).getByRole('link', { name: 'Stripe payments' }))
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/incoming-sources/${SOURCE.id}`);
  });

  it('says where to start receiving when there is no source yet', async () => {
    arrange();
    renderConnections();

    const incoming = await screen.findByRole('region', { name: 'Incoming' });
    expect(await within(incoming).findByRole('link', { name: 'Add a source' }))
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/incoming-sources`);
  });

  it('has no detectable axe violations', async () => {
    arrange({ sources: [SOURCE] });
    const { container } = renderConnections();
    await screen.findByText(DESTINATION.url);
    expect(await axe(container)).toHaveNoViolations();
  });
});
