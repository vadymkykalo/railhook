import { describe, it, expect, vi, beforeEach } from 'vitest';
import { waitFor } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ReplaySessionResponse } from '../../api/replay.api';

vi.mock('../../api/replay.api', () => ({
  replayApi: {
    estimate: vi.fn(),
    create: vi.fn(),
    get: vi.fn(),
    list: vi.fn(),
    cancel: vi.fn(),
  },
}));

vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn().mockResolvedValue([]) },
}));

// The page loads the project alongside the sessions; without this the whole load rejects and
// the list never renders, which would make every assertion below pass for the wrong reason.
vi.mock('../../api/projects.api', () => ({
  projectsApi: {
    get: vi.fn().mockResolvedValue({ id: 'project-1', name: 'Test Project' }),
  },
}));

import ReplayPage from '../ReplayPage';
import { replayApi } from '../../api/replay.api';

/**
 * Replay re-sends real events to real endpoints, which is why it has an estimate step at all —
 * and why the page shipping with no test is worse than most. The thing to hold down is that
 * opening it does not start one: a page that estimates on mount is a page that could as easily
 * create on mount, and the difference is somebody's customers receiving a few thousand webhooks
 * a second time.
 */

const page = (content: ReplaySessionResponse[]) => ({
  content,
  totalElements: content.length,
  totalPages: 1,
  size: 50,
  number: 0,
  first: true,
  last: true,
});

const RUNNING: ReplaySessionResponse = {
  id: 'session-1',
  projectId: TEST_PROJECT_ID,
  createdBy: 'user-1',
  status: 'RUNNING',
  fromDate: new Date('2026-08-01T00:00:00Z').toISOString(),
  toDate: new Date('2026-08-02T00:00:00Z').toISOString(),
  totalEvents: 1200,
  processedEvents: 300,
  deliveriesCreated: 300,
  errors: 0,
  progressPercent: 25,
} as ReplaySessionResponse;

const COMPLETED: ReplaySessionResponse = {
  ...RUNNING,
  id: 'session-2',
  status: 'COMPLETED',
  processedEvents: 1200,
  deliveriesCreated: 1200,
  progressPercent: 100,
};

function renderReplay() {
  return renderPage(<ReplayPage />, {
    path: '/projects/:projectId/replay',
    initialEntry: `/projects/${TEST_PROJECT_ID}/replay`,
  });
}

describe('ReplayPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(replayApi.list).mockResolvedValue(page([]));
  });

  it('does not replay anything by being opened', async () => {
    renderReplay();

    await waitFor(() => expect(replayApi.list).toHaveBeenCalled());
    expect(replayApi.create).not.toHaveBeenCalled();
    expect(replayApi.estimate).not.toHaveBeenCalled();
  });

  it('shows a running session with its progress', async () => {
    vi.mocked(replayApi.list).mockResolvedValue(page([RUNNING]));
    renderReplay();

    await waitFor(() => expect(replayApi.list).toHaveBeenCalled());
    // The count is what tells an operator whether to let it finish or cancel it.
    await waitFor(() => expect(document.body.textContent).toMatch(/300|1[,.\s]?200|25/));
  });

  it('shows a completed session', async () => {
    vi.mocked(replayApi.list).mockResolvedValue(page([COMPLETED]));
    renderReplay();

    await waitFor(() => expect(replayApi.list).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent).toMatch(/complete/i));
  });

  it('renders something when the session list fails to load', async () => {
    vi.mocked(replayApi.list).mockRejectedValue(new Error('boom'));
    renderReplay();

    await waitFor(() => expect(replayApi.list).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
  });

  it('renders an empty history without breaking', async () => {
    renderReplay();

    await waitFor(() => expect(replayApi.list).toHaveBeenCalled());
    expect(document.body.textContent?.trim()).not.toBe('');
  });
});
