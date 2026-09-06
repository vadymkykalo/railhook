import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, DeliveryResponse, PageResponse, EndpointResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn(), listPaged: vi.fn() },
}));
vi.mock('../../api/deliveries.api', () => ({
  deliveriesApi: {
    listByProject: vi.fn(),
    get: vi.fn(),
    replay: vi.fn(),
    dryRunReplay: vi.fn(),
    replayFromAttempt: vi.fn(),
    bulkReplay: vi.fn(),
    getAttempts: vi.fn(),
  },
}));
vi.mock('../../api/events.api', () => ({
  eventsApi: { get: vi.fn(), listByProject: vi.fn(), sendTestEvent: vi.fn() },
}));

import DeliveriesPage from '../DeliveriesPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { deliveriesApi } from '../../api/deliveries.api';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1',
  projectId: TEST_PROJECT_ID,
  url: 'https://example.com/webhook',
  enabled: true,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const DELIVERY: DeliveryResponse = {
  id: 'delivery-1',
  eventId: 'event-1',
  endpointId: ENDPOINT.id,
  subscriptionId: 'sub-1',
  status: 'SUCCESS',
  attemptCount: 1,
  maxAttempts: 5,
  createdAt: new Date().toISOString(),
};

function emptyPage(): PageResponse<DeliveryResponse> {
  return { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 } as any;
}

function populatedPage(items: DeliveryResponse[]): PageResponse<DeliveryResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0 } as any;
}

function renderDeliveries() {
  return renderPage(<DeliveriesPage />, {
    path: '/projects/:projectId/deliveries',
    initialEntry: `/projects/${TEST_PROJECT_ID}/deliveries`,
  });
}

describe('DeliveriesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
  });

  it('renders a loading skeleton before data arrives', () => {
    vi.mocked(projectsApi.get).mockReturnValue(new Promise(() => {}));
    vi.mocked(deliveriesApi.listByProject).mockReturnValue(new Promise(() => {}));
    const { container } = renderDeliveries();
    expect(container.querySelector('.animate-pulse')).toBeTruthy();
  });

  it('renders the onboarding empty state when there are genuinely no deliveries', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(emptyPage());
    renderDeliveries();
    expect(await screen.findByText(/no deliveries found/i)).toBeInTheDocument();
  });

  it('renders populated rows when deliveries exist', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(populatedPage([DELIVERY]));
    renderDeliveries();
    expect(await screen.findByText('https://example.com/webhook')).toBeInTheDocument();
  });

  it('has no detectable axe accessibility violations when populated', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(populatedPage([DELIVERY]));
    const { container } = renderDeliveries();
    await screen.findByText('https://example.com/webhook');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('renders an explicit error state — not "no deliveries found" — when the API is down (the outage-debugging case)', async () => {
    vi.mocked(projectsApi.get).mockRejectedValue({ request: {}, message: 'Network Error' });
    vi.mocked(deliveriesApi.listByProject).mockRejectedValue({ request: {}, message: 'Network Error' });
    renderDeliveries();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText(/no deliveries found/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });
});
