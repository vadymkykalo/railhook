import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { DeliveryResponse, EndpointResponse, PageResponse, ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({ projectsApi: { get: vi.fn(), list: vi.fn() } }));
vi.mock('../../api/endpoints.api', () => ({ endpointsApi: { list: vi.fn(), test: vi.fn() } }));
vi.mock('../../api/subscriptions.api', () => ({ subscriptionsApi: { list: vi.fn() } }));
vi.mock('../../api/schemas.api', () => ({ schemasApi: { listEventTypes: vi.fn() } }));
vi.mock('../../api/events.api', () => ({ eventsApi: { sendTestEvent: vi.fn() } }));
vi.mock('../../api/deliveries.api', () => ({ deliveriesApi: { listByProject: vi.fn(), getAttempts: vi.fn() } }));

import TestConsolePage from '../TestConsolePage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { subscriptionsApi } from '../../api/subscriptions.api';
import { schemasApi } from '../../api/schemas.api';
import { eventsApi } from '../../api/events.api';
import { deliveriesApi } from '../../api/deliveries.api';

const now = new Date().toISOString();
const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID, name: 'Test Project', schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN', idempotencyPolicy: 'NONE', createdAt: now, updatedAt: now,
};
const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1', projectId: TEST_PROJECT_ID, url: 'https://example.com/hook', enabled: true, createdAt: now, updatedAt: now,
};

function pendingFor(eventId: string): PageResponse<DeliveryResponse> {
  return {
    content: [{
      id: `delivery-${eventId}`, eventId, endpointId: ENDPOINT.id, subscriptionId: 's',
      status: 'PENDING', attemptCount: 0, maxAttempts: 5, createdAt: now,
    }],
    totalElements: 1, totalPages: 1, size: 50, number: 0,
  } as PageResponse<DeliveryResponse>;
}

const callsFor = (eventId: string) =>
  vi.mocked(deliveriesApi.listByProject).mock.calls.filter(([, filters]) => filters?.eventId === eventId).length;

async function sendEvent() {
  fireEvent.change(screen.getByLabelText(/event type/i), { target: { value: 'order.created' } });
  fireEvent.click(screen.getByRole('button', { name: /send & inspect/i }));
}

describe('TestConsolePage delivery polling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
    vi.mocked(subscriptionsApi.list).mockResolvedValue([]);
    vi.mocked(schemasApi.listEventTypes).mockResolvedValue([]);
    vi.mocked(deliveriesApi.listByProject).mockImplementation(async (_p, filters) => pendingFor(filters!.eventId!));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('stops polling the previous event once a new one is sent', async () => {
    vi.mocked(eventsApi.sendTestEvent)
      .mockResolvedValueOnce({ id: 'event-1', deliveriesCreated: 1 } as never)
      .mockResolvedValueOnce({ id: 'event-2', deliveriesCreated: 1 } as never);
    renderPage(<TestConsolePage />, { path: '/projects/:projectId/test-console', initialEntry: `/projects/${TEST_PROJECT_ID}/test-console` });
    await screen.findByLabelText(/event type/i);

    await sendEvent();
    await act(async () => { vi.advanceTimersByTime(1100); });
    await waitFor(() => expect(callsFor('event-1')).toBe(1));

    await sendEvent();
    await act(async () => { vi.advanceTimersByTime(1100); });
    await waitFor(() => expect(callsFor('event-2')).toBe(1));

    await act(async () => { vi.advanceTimersByTime(10_000); });
    expect(callsFor('event-1')).toBe(1);
  });

  it('stops polling when the page unmounts', async () => {
    vi.mocked(eventsApi.sendTestEvent).mockResolvedValue({ id: 'event-1', deliveriesCreated: 1 } as never);
    const { unmount } = renderPage(<TestConsolePage />, { path: '/projects/:projectId/test-console', initialEntry: `/projects/${TEST_PROJECT_ID}/test-console` });
    await screen.findByLabelText(/event type/i);

    await sendEvent();
    await act(async () => { vi.advanceTimersByTime(1100); });
    await waitFor(() => expect(callsFor('event-1')).toBe(1));

    unmount();
    await act(async () => { vi.advanceTimersByTime(10_000); });
    expect(callsFor('event-1')).toBe(1);
  });
});
