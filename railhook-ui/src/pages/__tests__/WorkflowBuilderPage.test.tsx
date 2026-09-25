import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
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

  it('opens a workflow whose nodes carry no position', async () => {
    vi.mocked(workflowsApi.get).mockResolvedValue({
      ...WORKFLOW,
      definition: {
        nodes: [
          { id: 'start', type: 'webhookTrigger', data: {} },
          { id: 'reshape', type: 'transform', data: { template: '{"a":1}' } },
        ],
        edges: [{ source: 'start', target: 'reshape' }],
      } as unknown as WorkflowResponse['definition'],
    });

    renderBuilder();

    expect(await screen.findByText('Route payments')).toBeInTheDocument();
    expect(screen.queryByText(/reading 'x'/)).not.toBeInTheDocument();
  });

  it('offers every node type the canvas can draw', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

    const palette = document.body.textContent ?? '';
    for (const label of [/webhook/i, /filter|фільтр/i, /transform|трансформац/i, /http/i, /slack/i, /delay|затримк/i]) {
      expect(palette).toMatch(label);
    }
  });

  it('adds a node when a palette entry is tapped', async () => {
    renderBuilder();
    await screen.findByText('Route payments');
    await waitFor(() => expect(document.body.textContent).toMatch(/1 nodes/));

    await userEvent.click(screen.getByRole('button', { name: /delay|затримк/i }));

    await waitFor(() => expect(document.body.textContent).toMatch(/2 nodes/));
    expect(screen.getByText(/unsaved/i)).toBeInTheDocument();
  });

  it('opens a workflow with nothing to save', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

    await waitFor(() => expect(screen.getByRole('button', { name: /^save|^зберегти/i })).toBeDisabled());
    expect(screen.queryByText(/unsaved|не збережено/i)).not.toBeInTheDocument();
  });

  it('saves nothing, enables nothing and runs nothing by being opened', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

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

    expect(workflowsApi.trigger).not.toHaveBeenCalled();
  });

  it('shows a disabled workflow as disabled', async () => {
    renderBuilder();

    await screen.findByText('Route payments');
    await waitFor(() => expect(document.body.textContent).toMatch(/disabled|вимкнено/i));
  });

  it('keeps unsaved canvas edits when the workflow is enabled or disabled', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

    fireEvent.click(await screen.findByText('Reshape'));
    fireEvent.keyDown(window, { key: 'Delete' });
    await waitFor(() => expect(screen.queryByText('Reshape')).not.toBeInTheDocument());
    expect(screen.getByText(/unsaved/i)).toBeInTheDocument();

    vi.mocked(workflowsApi.toggle).mockResolvedValue({ ...WORKFLOW, enabled: true } as never);
    vi.mocked(workflowsApi.get).mockResolvedValue({ ...WORKFLOW, enabled: true });
    fireEvent.click(screen.getByRole('button', { name: /^disabled$/i }));

    await screen.findByRole('button', { name: /^enabled$/i });
    expect(screen.getByText(/unsaved/i)).toBeInTheDocument();
    expect(screen.queryByText('Reshape')).not.toBeInTheDocument();
  });

  it('renders something rather than a blank page when the workflow fails to load', async () => {
    vi.mocked(workflowsApi.get).mockRejectedValue(new Error('boom'));
    renderBuilder();

    await waitFor(() => expect(workflowsApi.get).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
  });
});
