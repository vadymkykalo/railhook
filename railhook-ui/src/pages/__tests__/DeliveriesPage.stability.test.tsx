import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
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

const now = new Date().toISOString();
const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID, name: 'Test Project', schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN', idempotencyPolicy: 'NONE', createdAt: now, updatedAt: now,
};
const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1', projectId: TEST_PROJECT_ID, url: 'https://example.com/webhook',
  enabled: true, createdAt: now, updatedAt: now,
};
const DELIVERY: DeliveryResponse = {
  id: 'delivery-1', eventId: 'event-1', endpointId: ENDPOINT.id, subscriptionId: 'sub-1',
  status: 'SUCCESS', attemptCount: 1, maxAttempts: 5, createdAt: now,
};

function page(items: DeliveryResponse[]): PageResponse<DeliveryResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0 } as PageResponse<DeliveryResponse>;
}

function renderDeliveries() {
  return renderPage(<DeliveriesPage />, {
    path: '/projects/:projectId/deliveries',
    initialEntry: `/projects/${TEST_PROJECT_ID}/deliveries`,
  });
}

describe('DeliveriesPage across the minute tick', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(deliveriesApi.get).mockResolvedValue(DELIVERY);
    vi.mocked(deliveriesApi.getAttempts).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('asks for an open-ended window, so deliveries created this minute are not cut off', async () => {
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(page([DELIVERY]));
    renderDeliveries();
    await screen.findByText(ENDPOINT.url);

    const filters = vi.mocked(deliveriesApi.listByProject).mock.calls[0][1];
    expect(filters?.fromDate).toBeDefined();
    expect(filters?.toDate).toBeUndefined();
  });

  it('keeps the open delivery sheet mounted while the window advances and refetches', async () => {
    vi.mocked(deliveriesApi.listByProject)
      .mockResolvedValueOnce(page([DELIVERY]))
      .mockReturnValue(new Promise(() => {}));
    renderDeliveries();
    fireEvent.click(await screen.findByText(ENDPOINT.url, { selector: 'td a span' }).then((a) => a.closest('tr')!));
    await screen.findByRole('dialog');

    await act(async () => { vi.advanceTimersByTime(61_000); });

    await waitFor(() => expect(deliveriesApi.listByProject).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(document.querySelector('.animate-pulse')).toBeNull();
  });
});
