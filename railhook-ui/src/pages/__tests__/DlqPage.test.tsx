import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, EndpointResponse } from '../../types/api.types';
import type { DlqItemResponse, DlqStatsResponse, PageResponse } from '../../api/dlq.api';

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

const DLQ_ITEM: DlqItemResponse = {
  deliveryId: 'delivery-1',
  eventId: 'event-1',
  endpointId: ENDPOINT.id,
  subscriptionId: 'sub-1',
  eventType: 'order.created',
  endpointUrl: ENDPOINT.url,
  attemptCount: 5,
  maxAttempts: 5,
  lastError: 'connection timed out',
  failedAt: new Date().toISOString(),
  createdAt: new Date().toISOString(),
};

const EMPTY_STATS: DlqStatsResponse = { totalItems: 0, last24Hours: 0, last7Days: 0 };
const POPULATED_STATS: DlqStatsResponse = { totalItems: 1, last24Hours: 1, last7Days: 1 };

function emptyPage(): PageResponse<DlqItemResponse> {
  return { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 };
}
function populatedPage(items: DlqItemResponse[]): PageResponse<DlqItemResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0 };
}

function renderDlq() {
  return renderPage(<DlqPage />, {
    path: '/projects/:projectId/dlq',
    initialEntry: `/projects/${TEST_PROJECT_ID}/dlq`,
  });
}

describe('DlqPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
  });

  it('renders a loading skeleton before data arrives', () => {
    vi.mocked(projectsApi.get).mockReturnValue(new Promise(() => {}));
    vi.mocked(dlqApi.list).mockReturnValue(new Promise(() => {}));
    vi.mocked(dlqApi.getStats).mockReturnValue(new Promise(() => {}));
    const { container } = renderDlq();
    expect(container.querySelector('.animate-pulse')).toBeTruthy();
  });

  it('renders the empty state when the DLQ is genuinely empty', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(dlqApi.list).mockResolvedValue(emptyPage());
    vi.mocked(dlqApi.getStats).mockResolvedValue(EMPTY_STATS);
    renderDlq();
    expect(await screen.findByText(/no failed messages|no items/i)).toBeInTheDocument();
  });

  it('renders populated rows when DLQ items exist', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(dlqApi.list).mockResolvedValue(populatedPage([DLQ_ITEM]));
    vi.mocked(dlqApi.getStats).mockResolvedValue(POPULATED_STATS);
    renderDlq();
    expect(await screen.findByText('order.created')).toBeInTheDocument();
  });

  it('has no detectable axe accessibility violations when populated', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(dlqApi.list).mockResolvedValue(populatedPage([DLQ_ITEM]));
    vi.mocked(dlqApi.getStats).mockResolvedValue(POPULATED_STATS);
    const { container } = renderDlq();
    await screen.findByText('order.created');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('renders an explicit error state — not the empty state — when the API is down', async () => {
    vi.mocked(projectsApi.get).mockRejectedValue({ request: {}, message: 'Network Error' });
    vi.mocked(dlqApi.list).mockRejectedValue({ request: {}, message: 'Network Error' });
    vi.mocked(dlqApi.getStats).mockRejectedValue({ request: {}, message: 'Network Error' });
    renderDlq();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText(/no failed messages|no items/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });
});
