import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, PageResponse } from '../../types/api.types';
import type { EventResponse } from '../../api/events.api';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/events.api', () => ({
  eventsApi: { listByProject: vi.fn(), get: vi.fn(), sendTestEvent: vi.fn() },
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
});
