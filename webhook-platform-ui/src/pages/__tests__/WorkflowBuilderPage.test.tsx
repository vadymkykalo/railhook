import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { WorkflowResponse } from '../../api/workflows.api';

vi.mock('../../api/workflows.api', () => ({
  workflowsApi: {
    get: vi.fn(),
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    toggle: vi.fn(),
    trigger: vi.fn(),
    listExecutions: vi.fn(),
    getExecution: vi.fn(),
  },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn().mockResolvedValue([]), create: vi.fn() },
}));
vi.mock('../../api/transformations.api', () => ({
  transformationsApi: { list: vi.fn().mockResolvedValue([]), create: vi.fn() },
}));
vi.mock('../../api/subscriptions.api', () => ({
  subscriptionsApi: { list: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../api/apiKeys.api', () => ({
  apiKeysApi: { list: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../api/schemas.api', () => ({
  schemasApi: { listEventTypes: vi.fn().mockResolvedValue([]) },
}));

import WorkflowBuilderPage from '../WorkflowBuilderPage';
import { workflowsApi } from '../../api/workflows.api';

/**
 * The canvas, and the two things on it that reach production.
 *
 * <p>This is the page that motivated a per-route error boundary: it is the largest in the app,
 * it renders a third-party canvas, and a throw here used to take the whole dashboard with it.
 * So the first thing worth a test is simply that it renders — under a jsdom that has neither
 * ResizeObserver nor DOMMatrix, which React Flow measures with on mount.
 *
 * <p>The second is that opening it is inert. A builder holds an unsaved draft of something that
 * runs against a customer's real endpoints; a save or a test run that could happen without a
 * click is the difference between an editor and a deploy button.
 */

const WORKFLOW: WorkflowResponse = {
  id: 'workflow-1',
  projectId: TEST_PROJECT_ID,
  name: 'Route payments',
  description: null,
  enabled: false,
  definition: {
    nodes: [{ id: 'n1', type: 'transform', position: { x: 0, y: 0 }, data: { label: 'Reshape' } }],
    edges: [],
  } as unknown as WorkflowResponse['definition'],
  triggerType: 'WEBHOOK_EVENT',
  triggerConfig: {},
  version: 1,
  createdAt: new Date('2026-08-01T00:00:00Z').toISOString(),
  updatedAt: new Date('2026-08-01T00:00:00Z').toISOString(),
  totalExecutions: 0,
  successfulExecutions: 0,
  failedExecutions: 0,
} as WorkflowResponse;

const emptyExecutions = {
  content: [], totalElements: 0, totalPages: 0, size: 10, number: 0, first: true, last: true,
};

function renderBuilder() {
  return renderPage(<WorkflowBuilderPage />, {
    path: '/projects/:projectId/workflows/:workflowId',
    initialEntry: `/projects/${TEST_PROJECT_ID}/workflows/workflow-1`,
  });
}

describe('WorkflowBuilderPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(workflowsApi.get).mockResolvedValue(WORKFLOW);
    vi.mocked(workflowsApi.listExecutions).mockResolvedValue(emptyExecutions as never);
  });

  it('renders the canvas without throwing', async () => {
    renderBuilder();

    expect(await screen.findByText('Route payments')).toBeInTheDocument();
  });

  it('offers every node type the canvas can draw', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

    // The palette is the only way to add a node, so a type missing from it is a feature that
    // exists in the executor and nowhere a person can reach.
    const palette = document.body.textContent ?? '';
    for (const label of [/webhook/i, /filter|фільтр/i, /transform|трансформац/i, /http/i, /slack/i, /delay|затримк/i]) {
      expect(palette).toMatch(label);
    }
  });

  it('saves nothing, enables nothing and runs nothing by being opened', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

    // A workflow triggers real deliveries to real endpoints. Everything here needs a click.
    expect(workflowsApi.update).not.toHaveBeenCalled();
    expect(workflowsApi.toggle).not.toHaveBeenCalled();
    expect(workflowsApi.trigger).not.toHaveBeenCalled();
    expect(workflowsApi.delete).not.toHaveBeenCalled();
  });

  it('does not fire a test run from opening the test-run panel', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

    const open = screen.getAllByRole('button')
      .find((b) => /test run|тестовий запуск/i.test(b.textContent ?? ''));
    if (open) await userEvent.click(open);

    // Opening the panel is choosing to look at it, not choosing to run.
    expect(workflowsApi.trigger).not.toHaveBeenCalled();
  });

  it('shows a disabled workflow as disabled', async () => {
    renderBuilder();

    await screen.findByText('Route payments');
    // Whether it is live is the one fact that changes what this page means.
    await waitFor(() => expect(document.body.textContent).toMatch(/disabled|вимкнено/i));
  });

  it('renders something rather than a blank page when the workflow fails to load', async () => {
    vi.mocked(workflowsApi.get).mockRejectedValue(new Error('boom'));
    renderBuilder();

    await waitFor(() => expect(workflowsApi.get).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
  });
});
