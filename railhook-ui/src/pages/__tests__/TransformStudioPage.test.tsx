import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type {
  EndpointResponse, PageResponse, TransformationResponse,
} from '../../types/api.types';
import type { TransformPreviewResponse } from '../../api/transform.api';

vi.mock('../../api/transform.api', () => ({
  transformApi: { preview: vi.fn(), deliveryDryRun: vi.fn() },
}));
vi.mock('../../api/transformations.api', () => ({
  transformationsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
}));
vi.mock('../../api/events.api', () => ({ eventsApi: { listByProject: vi.fn() } }));
vi.mock('../../api/endpoints.api', () => ({ endpointsApi: { list: vi.fn() } }));

import TransformStudioPage from '../TransformStudioPage';
import { transformApi } from '../../api/transform.api';
import { transformationsApi } from '../../api/transformations.api';
import { eventsApi } from '../../api/events.api';
import { endpointsApi } from '../../api/endpoints.api';

const now = new Date().toISOString();

const SCRIPT_TRANSFORMATION: TransformationResponse = {
  id: 'tr-js', projectId: TEST_PROJECT_ID, name: 'Order lines',
  template: 'function handler(webhook) { return { payload: webhook.payload }; }',
  kind: 'JAVASCRIPT', version: 3, enabled: true,
  subscriptionCount: 1, destinationCount: 0, createdAt: now, updatedAt: now,
};

const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1', projectId: TEST_PROJECT_ID, url: 'https://example.com/hook',
  enabled: true, createdAt: now, updatedAt: now,
};

const NO_EVENTS = { content: [], totalElements: 0, totalPages: 0, size: 10, number: 0, last: true } as unknown as PageResponse<unknown>;

function previewOf(partial: Partial<TransformPreviewResponse>): TransformPreviewResponse {
  return {
    outputPayload: null, outputHeaders: null, success: true, errors: [],
    kind: 'JAVASCRIPT', console: [], consoleTruncated: false, cancelled: false,
    cancelReason: null, durationMs: 4, errorLine: null,
    ...partial,
  };
}

function open(path = `/projects/${TEST_PROJECT_ID}/transform-studio`) {
  return renderPage(<TransformStudioPage />, {
    path: '/projects/:projectId/transform-studio',
    initialEntry: path,
  });
}

const runButton = () => screen.getByRole('button', { name: /run preview/i });

// Three CodeMirror instances outrun the default 5s on a loaded CI runner.
describe('TransformStudioPage', { timeout: 20_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(transformationsApi.list).mockResolvedValue([SCRIPT_TRANSFORMATION]);
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
    vi.mocked(eventsApi.listByProject).mockResolvedValue(NO_EVENTS as never);
    vi.mocked(transformationsApi.update).mockResolvedValue(SCRIPT_TRANSFORMATION);
    vi.mocked(transformationsApi.create).mockResolvedValue(SCRIPT_TRANSFORMATION);
  });

  it('runs the script and shows the body it returned', async () => {
    vi.mocked(transformApi.preview).mockResolvedValue(previewOf({
      outputPayload: '{\n  "order": "ord_9001"\n}',
    }));

    open();
    fireEvent.click(runButton());

    await waitFor(() => expect(transformApi.preview).toHaveBeenCalled());
    const [, request] = vi.mocked(transformApi.preview).mock.calls[0];
    expect(request.kind).toBe('JAVASCRIPT');
    expect(request.template).toContain('function handler(webhook)');

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /output/i })).toHaveAttribute('aria-selected', 'true');
    });
  });

  it('puts a failing script in the Console tab, with its line, and does not toast it away', async () => {
    vi.mocked(transformApi.preview).mockResolvedValue(previewOf({
      success: false,
      errors: ['RUNTIME (line 4): Error: boom'],
      errorLine: 4,
      console: [{ level: 'log', message: 'got here' }],
    }));

    open();
    fireEvent.click(runButton());

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /console/i })).toHaveAttribute('aria-selected', 'true');
    });
    const consolePanel = screen.getByRole('tabpanel', { hidden: false });
    expect(within(consolePanel).getByText(/RUNTIME \(line 4\): Error: boom/)).toBeInTheDocument();
    expect(within(consolePanel).getByText('line 4')).toBeInTheDocument();
    expect(within(consolePanel).getByText('got here')).toBeInTheDocument();
  });

  it('shows what the script logged even when the run succeeded', async () => {
    vi.mocked(transformApi.preview).mockResolvedValue(previewOf({
      outputPayload: '{}',
      console: [
        { level: 'log', message: 'lines 2 value 101.48' },
        { level: 'warn', message: 'no review needed' },
      ],
    }));

    open();
    fireEvent.click(runButton());
    await waitFor(() => expect(transformApi.preview).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('tab', { name: /console/i }));
    expect(await screen.findByText('lines 2 value 101.48')).toBeInTheDocument();
    expect(screen.getByText('no review needed')).toBeInTheDocument();
  });

  it('says plainly when a script cancelled the delivery, rather than showing an empty body', async () => {
    vi.mocked(transformApi.preview).mockResolvedValue(previewOf({
      outputPayload: null, cancelled: true, cancelReason: 'test traffic',
    }));

    open();
    fireEvent.click(runButton());

    expect(await screen.findByText(/cancelled this delivery/i)).toBeInTheDocument();
    expect(screen.getByText('test traffic')).toBeInTheDocument();
    expect(screen.getByText(/does not appear in Failed Messages/i)).toBeInTheDocument();
  });

  it('diffs the returned body against the event it was given', async () => {
    vi.mocked(transformApi.preview).mockResolvedValue(previewOf({
      outputPayload: '{\n  "order": "ord_9001"\n}',
    }));

    open();
    fireEvent.click(runButton());
    await waitFor(() => expect(transformApi.preview).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('tab', { name: /diff/i }));
    expect(await screen.findByText('"order": "ord_9001"')).toBeInTheDocument();
  });

  it('saves the script back into the transformation it was opened with', async () => {
    open(`/projects/${TEST_PROJECT_ID}/transform-studio?transformation=tr-js`);

    const save = await screen.findByRole('button', { name: /^save script$/i });
    fireEvent.click(save);

    await waitFor(() => expect(transformationsApi.update).toHaveBeenCalled());
    const [, id, body] = vi.mocked(transformationsApi.update).mock.calls[0];
    expect(id).toBe('tr-js');
    expect(body.kind).toBe('JAVASCRIPT');
    expect(body.name).toBe('Order lines');
    expect(body.template).toBe(SCRIPT_TRANSFORMATION.template);
  });

  it('runs the saved transformation by id only while the editor still matches it', async () => {
    vi.mocked(transformApi.preview).mockResolvedValue(previewOf({ outputPayload: '{}' }));

    open(`/projects/${TEST_PROJECT_ID}/transform-studio?transformation=tr-js`);
    await screen.findByRole('button', { name: /^save script$/i });

    fireEvent.click(runButton());
    await waitFor(() => expect(transformApi.preview).toHaveBeenCalled());
    const [, untouched] = vi.mocked(transformApi.preview).mock.calls[0];
    expect(untouched.transformationId).toBe('tr-js');
    expect(untouched.template).toBeUndefined();
  });

  it('asks for a name when there is no transformation to save into', async () => {
    open();

    fireEvent.click(screen.getByRole('button', { name: /save as transformation/i }));
    expect(await screen.findByText(/save as a new transformation/i)).toBeInTheDocument();
    expect(transformationsApi.create).not.toHaveBeenCalled();
  });
});
