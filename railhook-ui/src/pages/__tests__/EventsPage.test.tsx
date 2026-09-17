import type { ReactNode } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { DeliveryStatusCounts, ProjectResponse, PageResponse } from '../../types/api.types';
import type { EventResponse } from '../../api/events.api';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/events.api', () => ({
  eventsApi: { listByProject: vi.fn(), get: vi.fn(), sendTestEvent: vi.fn() },
}));
// Radix Select's listbox never opens in jsdom; a native select keeps the same onChange contract,
// so the status filter can be changed the way an operator would.
vi.mock('../../components/ui/select', () => ({
  Select: ({ id, value, onChange, children, ...rest }: {
    id?: string; value?: string; children?: ReactNode; 'aria-label'?: string;
    onChange?: (e: { target: { value: string } }) => void;
  }) => (
    <select id={id} aria-label={rest['aria-label']} value={value} onChange={(e) => onChange?.({ target: { value: e.target.value } })}>{children}</select>
  ),
}));

import EventsPage from '../EventsPage';
import { projectsApi } from '../../api/projects.api';
import { eventsApi } from '../../api/events.api';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const EVENT: EventResponse = {
  id: 'event-1',
  projectId: TEST_PROJECT_ID,
  eventType: 'order.created',
  payload: '{}',
  createdAt: new Date().toISOString(),
  deliveriesCreated: 2,
};

function emptyPage(): PageResponse<EventResponse> {
  return { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 } as any;
}

function populatedPage(items: EventResponse[]): PageResponse<EventResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0 } as any;
}

function renderEvents() {
  return renderPage(<EventsPage />, {
    path: '/projects/:projectId/events',
    initialEntry: `/projects/${TEST_PROJECT_ID}/events`,
  });
}

describe('EventsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders a loading skeleton before data arrives', () => {
    vi.mocked(projectsApi.get).mockReturnValue(new Promise(() => {}));
    vi.mocked(eventsApi.listByProject).mockReturnValue(new Promise(() => {}));
    const { container } = renderEvents();
    expect(container.querySelector('.animate-pulse')).toBeTruthy();
  });

  it('renders the onboarding empty state when there are genuinely no events', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(eventsApi.listByProject).mockResolvedValue(emptyPage());
    renderEvents();
    expect(await screen.findByText(/no events yet/i)).toBeInTheDocument();
  });

  it('renders populated rows when events exist', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(eventsApi.listByProject).mockResolvedValue(populatedPage([EVENT]));
    renderEvents();
    expect(await screen.findByText('order.created')).toBeInTheDocument();
  });

  it('has no detectable axe accessibility violations when populated', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(eventsApi.listByProject).mockResolvedValue(populatedPage([EVENT]));
    const { container } = renderEvents();
    await screen.findByText('order.created');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('renders an explicit error state — not "no events yet" — when the API 500s', async () => {
    vi.mocked(projectsApi.get).mockRejectedValue({ response: { status: 500 } });
    vi.mocked(eventsApi.listByProject).mockRejectedValue({ response: { status: 500 } });
    renderEvents();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText(/no events yet/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });
  it('keeps the search box and the rows mounted while a debounced search loads', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(eventsApi.listByProject)
      .mockResolvedValueOnce(populatedPage([EVENT]))
      .mockReturnValue(new Promise(() => {}));
    const { container } = renderEvents();
    await screen.findByText('order.created');

    const search = container.querySelector('#event-search') as HTMLInputElement;
    fireEvent.change(search, { target: { value: 'order' } });

    await waitFor(() => expect(eventsApi.listByProject).toHaveBeenCalledTimes(2));
    expect(search).toBeInTheDocument();
    expect(search).toHaveValue('order');
    expect(screen.getByText('order.created')).toBeInTheDocument();
  });

  it('shows each row on page two what became of its own deliveries, however busy the project', async () => {
    const counts = (c: Partial<DeliveryStatusCounts>): DeliveryStatusCounts =>
      ({ pending: 0, processing: 0, success: 0, failed: 0, dlq: 0, ...c });
    const event = (id: string, eventType: string, deliveryCounts: DeliveryStatusCounts, deliveriesCreated: number): EventResponse =>
      ({ ...EVENT, id, eventType, deliveryCounts, deliveriesCreated });
    const firstPage = { content: [EVENT], totalElements: 24, totalPages: 2, size: 20, number: 0 } as any;
    const secondPage = {
      content: [
        event('event-delivered', 'invoice.paid', counts({ success: 15 }), 15),
        event('event-owed', 'invoice.sent', counts({ success: 12, pending: 2, processing: 1 }), 15),
        event('event-abandoned', 'invoice.voided', counts({ success: 13, dlq: 1, failed: 1 }), 15),
        event('event-unsubscribed', 'invoice.draft', counts({}), 0),
      ],
      totalElements: 24, totalPages: 2, size: 20, number: 1,
    } as any;
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(eventsApi.listByProject).mockImplementation(async (_projectId, filters) =>
      (filters?.page === 1 ? secondPage : firstPage));
    renderEvents();
    await screen.findByText('order.created');

    fireEvent.click(screen.getByRole('button', { name: /next/i }));
    await screen.findByText('invoice.paid');

    const rowOf = (eventType: string) => screen.getByText(eventType).closest('tr') as HTMLElement;
    expect(within(rowOf('invoice.paid')).getByText('Delivered')).toBeInTheDocument();
    expect(within(rowOf('invoice.paid')).getByText('15 of 15 delivered')).toBeInTheDocument();
    expect(within(rowOf('invoice.sent')).getByText('Still owed')).toBeInTheDocument();
    expect(within(rowOf('invoice.sent')).getByText('12 of 15 delivered')).toBeInTheDocument();
    expect(within(rowOf('invoice.voided')).getByText('Abandoned')).toBeInTheDocument();
    expect(within(rowOf('invoice.voided')).getByText('13 of 15 delivered')).toBeInTheDocument();
    expect(within(rowOf('invoice.draft')).getByText('No subscriber')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Delivery status'), { target: { value: 'abandoned' } });
    expect(screen.getByText('invoice.voided')).toBeInTheDocument();
    expect(screen.queryByText('invoice.paid')).not.toBeInTheDocument();
    expect(screen.queryByText('invoice.sent')).not.toBeInTheDocument();
  });

  it('keeps checking while a row still owes deliveries, so it turns delivered without a reload', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const owed = { ...EVENT, deliveryCounts: { pending: 1, processing: 1, success: 0, failed: 0, dlq: 0 } };
      const done = { ...EVENT, deliveryCounts: { pending: 0, processing: 0, success: 2, failed: 0, dlq: 0 } };
      vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
      vi.mocked(eventsApi.listByProject)
        .mockResolvedValueOnce(populatedPage([owed]))
        .mockResolvedValue(populatedPage([done]));
      renderEvents();
      await screen.findByText('order.created');
      const row = () => screen.getByText('order.created').closest('tr') as HTMLElement;
      expect(within(row()).getByText('Still owed')).toBeInTheDocument();

      await vi.advanceTimersByTimeAsync(6000);

      await waitFor(() => expect(within(row()).getByText('Delivered')).toBeInTheDocument());
      expect(within(row()).getByText('2 of 2 delivered')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
