import type { ReactNode } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, EndpointResponse } from '../../types/api.types';
import type { DlqItemResponse, DlqStatsResponse, PageResponse } from '../../api/dlq.api';

// Radix Select never opens in jsdom; a native select keeps the same onChange contract.
vi.mock('../../components/ui/select', () => ({
  Select: ({ id, value, onChange, children }: {
    id?: string; value?: string; children?: ReactNode;
    onChange?: (e: { target: { value: string } }) => void;
  }) => (
    <select id={id} value={value} onChange={(e) => onChange?.({ target: { value: e.target.value } })}>{children}</select>
  ),
}));
vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn(), listPaged: vi.fn() },
}));
vi.mock('../../api/dlq.api', () => ({
  dlqApi: {
    list: vi.fn(),
    getStats: vi.fn(),
    getItem: vi.fn(),
    retrySingle: vi.fn(),
    retryBulk: vi.fn(),
    purgeAll: vi.fn(),
  },
}));

import DlqPage from '../DlqPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { dlqApi } from '../../api/dlq.api';

const now = new Date().toISOString();
const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID, name: 'Test Project', schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN', idempotencyPolicy: 'NONE', createdAt: now, updatedAt: now,
};
const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1', projectId: TEST_PROJECT_ID, url: 'https://example.com/webhook',
  enabled: true, createdAt: now, updatedAt: now,
};
const DLQ_ITEM: DlqItemResponse = {
  deliveryId: 'delivery-1', eventId: 'event-1', endpointId: ENDPOINT.id, subscriptionId: 'sub-1',
  eventType: 'order.created', endpointUrl: ENDPOINT.url, attemptCount: 5, maxAttempts: 5,
  lastError: 'connection timed out', failedAt: now, createdAt: now,
};
const STATS: DlqStatsResponse = { totalItems: 1, last24Hours: 1, last7Days: 1 };

function page(items: DlqItemResponse[]): PageResponse<DlqItemResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0 };
}

async function renderLoaded() {
  const view = renderPage(<DlqPage />, {
    path: '/projects/:projectId/dlq',
    initialEntry: `/projects/${TEST_PROJECT_ID}/dlq`,
  });
  await screen.findByText('order.created');
  await screen.findByRole('option', { name: ENDPOINT.url });
  return view;
}

const endpointFilter = () => screen.getByLabelText(/filter by endpoint/i);

describe('DlqPage filters', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(dlqApi.getStats).mockResolvedValue(STATS);
  });

  it('offers no search or date filter, because the DLQ API ignores them', async () => {
    vi.mocked(dlqApi.list).mockResolvedValue(page([DLQ_ITEM]));
    const { container } = await renderLoaded();
    expect(endpointFilter()).toBeInTheDocument();
    expect(container.querySelector('#dlq-search')).toBeNull();
    expect(container.querySelector('#dlq-from')).toBeNull();
    expect(container.querySelector('#dlq-to')).toBeNull();
  });

  it('keeps the table on screen while a new endpoint filter loads', async () => {
    vi.mocked(dlqApi.list)
      .mockResolvedValueOnce(page([DLQ_ITEM]))
      .mockReturnValue(new Promise(() => {}));
    await renderLoaded();

    fireEvent.change(endpointFilter(), { target: { value: ENDPOINT.id } });

    await waitFor(() => expect(dlqApi.list).toHaveBeenCalledTimes(2));
    expect(screen.getByText('order.created')).toBeInTheDocument();
    expect(endpointFilter()).toBeInTheDocument();
  });

  it('clears the selection when the endpoint filter changes, so a bulk replay never acts on rows outside it', async () => {
    vi.mocked(dlqApi.list).mockResolvedValue(page([DLQ_ITEM]));
    await renderLoaded();

    fireEvent.click(screen.getByRole('checkbox', { name: /select row/i }));
    expect(screen.getByRole('button', { name: /clear selection/i })).toBeInTheDocument();

    fireEvent.change(endpointFilter(), { target: { value: ENDPOINT.id } });

    await waitFor(() => expect(dlqApi.list).toHaveBeenCalledTimes(2));
    await screen.findByText('order.created');
    expect(screen.queryByRole('button', { name: /clear selection/i })).not.toBeInTheDocument();
  });
});
