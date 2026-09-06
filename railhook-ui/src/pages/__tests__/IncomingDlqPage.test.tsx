import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type {
  DlqStatsResponse, IncomingDlqItemResponse, PageResponse, ProjectResponse,
} from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/incomingDlq.api', () => ({
  incomingDlqApi: {
    list: vi.fn(),
    getStats: vi.fn(),
    getItem: vi.fn(),
    retrySingle: vi.fn(),
    retryBulk: vi.fn(),
    purgeAll: vi.fn(),
  },
}));

import IncomingDlqPage from '../IncomingDlqPage';
import { projectsApi } from '../../api/projects.api';
import { incomingDlqApi } from '../../api/incomingDlq.api';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const DLQ_ITEM: IncomingDlqItemResponse = {
  forwardAttemptId: 'attempt-1',
  incomingEventId: 'incoming-event-1',
  destinationId: 'destination-1',
  incomingSourceId: 'source-1',
  sourceName: 'Stripe',
  destinationUrl: 'https://billing.internal/hooks',
  attemptNumber: 5,
  maxAttempts: 5,
  responseCode: 503,
  lastError: 'Max attempts reached: Retryable HTTP 503',
  failedAt: new Date().toISOString(),
  createdAt: new Date().toISOString(),
};

const EMPTY_STATS: DlqStatsResponse = { totalItems: 0, last24Hours: 0, last7Days: 0 };
const POPULATED_STATS: DlqStatsResponse = { totalItems: 1, last24Hours: 1, last7Days: 1 };

function emptyPage(): PageResponse<IncomingDlqItemResponse> {
  return { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true };
}
function populatedPage(items: IncomingDlqItemResponse[]): PageResponse<IncomingDlqItemResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0, first: true, last: true };
}

function renderIncomingDlq() {
  return renderPage(<IncomingDlqPage />, {
    path: '/projects/:projectId/incoming-dlq',
    initialEntry: `/projects/${TEST_PROJECT_ID}/incoming-dlq`,
  });
}

describe('IncomingDlqPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders a loading skeleton before data arrives', () => {
    vi.mocked(projectsApi.get).mockReturnValue(new Promise(() => {}));
    vi.mocked(incomingDlqApi.list).mockReturnValue(new Promise(() => {}));
    vi.mocked(incomingDlqApi.getStats).mockReturnValue(new Promise(() => {}));
    const { container } = renderIncomingDlq();
    expect(container.querySelector('.animate-pulse')).toBeTruthy();
  });

  it('renders the empty state when nothing has been abandoned', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(incomingDlqApi.list).mockResolvedValue(emptyPage());
    vi.mocked(incomingDlqApi.getStats).mockResolvedValue(EMPTY_STATS);
    renderIncomingDlq();
    expect(await screen.findByText(/no abandoned forwards/i)).toBeInTheDocument();
  });

  it('names the source and the destination of each abandoned forward', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(incomingDlqApi.list).mockResolvedValue(populatedPage([DLQ_ITEM]));
    vi.mocked(incomingDlqApi.getStats).mockResolvedValue(POPULATED_STATS);
    renderIncomingDlq();
    expect(await screen.findByText('Stripe')).toBeInTheDocument();
    expect(screen.getByText('https://billing.internal/hooks')).toBeInTheDocument();
  });

  /** The whole point of the page: recovery that does not fan out to every destination. */
  it('retries one forward by its own attempt id', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(incomingDlqApi.list).mockResolvedValue(populatedPage([DLQ_ITEM]));
    vi.mocked(incomingDlqApi.getStats).mockResolvedValue(POPULATED_STATS);
    vi.mocked(incomingDlqApi.retrySingle).mockResolvedValue({ retried: 1 });
    renderIncomingDlq();

    await screen.findByText('Stripe');
    await userEvent.click(screen.getByRole('button', { name: /retry this forward/i }));

    await waitFor(() => expect(incomingDlqApi.retrySingle)
      .toHaveBeenCalledWith(TEST_PROJECT_ID, 'attempt-1'));
  });

  it('has no detectable axe accessibility violations when populated', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(incomingDlqApi.list).mockResolvedValue(populatedPage([DLQ_ITEM]));
    vi.mocked(incomingDlqApi.getStats).mockResolvedValue(POPULATED_STATS);
    const { container } = renderIncomingDlq();
    await screen.findByText('Stripe');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('renders an explicit error state — not the empty state — when the API is down', async () => {
    vi.mocked(projectsApi.get).mockRejectedValue({ request: {}, message: 'Network Error' });
    vi.mocked(incomingDlqApi.list).mockRejectedValue({ request: {}, message: 'Network Error' });
    vi.mocked(incomingDlqApi.getStats).mockRejectedValue({ request: {}, message: 'Network Error' });
    renderIncomingDlq();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText(/no abandoned forwards/i)).not.toBeInTheDocument();
  });
});
