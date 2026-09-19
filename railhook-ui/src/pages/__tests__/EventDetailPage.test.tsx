import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';

vi.mock('../../api/events.api', () => ({
  eventsApi: { get: vi.fn(), listByProject: vi.fn() },
}));
vi.mock('../../api/deliveries.api', () => ({
  deliveriesApi: { listByProject: vi.fn() },
}));
vi.mock('../../api/debugLinks.api', () => ({
  debugLinksApi: { listForEvent: vi.fn(), create: vi.fn() },
}));
vi.mock('../../api/schemas.api', () => ({
  schemasApi: { listEventTypes: vi.fn() },
}));

import EventDetailPage from '../EventDetailPage';
import { eventsApi } from '../../api/events.api';
import { deliveriesApi } from '../../api/deliveries.api';
import { debugLinksApi } from '../../api/debugLinks.api';
import { schemasApi } from '../../api/schemas.api';

/**
 * An event opened from a delivery, a search or a shared link had no way back but the browser's
 * back button, which leads wherever the person came from rather than to the list the event is in.
 */
describe('EventDetailPage — where it sits', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(eventsApi.get).mockResolvedValue({
      id: 'event-1', projectId: TEST_PROJECT_ID, eventType: 'order.created', payload: '{"id":1}',
      createdAt: new Date().toISOString(),
    } as never);
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(
      { content: [], totalElements: 0, totalPages: 0, size: 50, number: 0 } as never,
    );
    vi.mocked(debugLinksApi.listForEvent).mockResolvedValue([]);
    vi.mocked(schemasApi.listEventTypes).mockResolvedValue([]);
  });

  it('has a breadcrumb back to the outgoing events', async () => {
    renderPage(<EventDetailPage />, {
      path: '/admin/projects/:projectId/events/:eventId',
      initialEntry: `/admin/projects/${TEST_PROJECT_ID}/events/event-1`,
    });

    const crumbs = await screen.findByRole('navigation', { name: /breadcrumb/i });
    expect(within(crumbs).getAllByRole('link', { name: 'Outgoing events' })[0])
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/events`);
    expect(within(crumbs).getByText('order.created')).toBeInTheDocument();
  });
});
