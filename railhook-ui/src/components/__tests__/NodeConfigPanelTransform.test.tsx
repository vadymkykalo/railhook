import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Node } from '@xyflow/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { TransformationResponse } from '../../types/api.types';

vi.mock('../../api/transformations.api', () => ({
  transformationsApi: { list: vi.fn(), create: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn().mockResolvedValue([]), create: vi.fn() },
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

// CodeMirror does not run in jsdom; the editor is a textarea here.
vi.mock('../JsonEditor', () => ({
  default: ({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder?: string }) => (
    <textarea aria-label={placeholder ?? 'template'} value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

import NodeConfigPanel from '../workflow/NodeConfigPanel';
import { transformationsApi } from '../../api/transformations.api';

/**
 * A transform node can reach the project's transformation library.
 *
 * <p>It could not, and the gap was invisible: the node offered one box for a template, the
 * Transformations page offered a library of named ones, and nothing on the canvas said the two
 * were related — or that they are written in different languages. `${$.customer.email}` typed
 * into the node's box is a literal string; `{{data.customer}}` saved as a transformation is a
 * literal string. Neither errors.
 *
 * <p>So the source is an explicit choice, and choosing the library does not send anyone away to
 * fill it. What the tests below hold down is that the choice actually switches which field the
 * node carries — because the executor resolves a reference over a leftover template, and a
 * toggle that only changed the panel would leave the node running the wrong one.
 */

const TRANSFORMATION: TransformationResponse = {
  id: 'transformation-1',
  projectId: TEST_PROJECT_ID,
  name: 'Flatten the customer',
  template: '{"email":"${$.customer.email}"}',
  version: 1,
  enabled: true,
  createdAt: new Date('2026-08-01T00:00:00Z').toISOString(),
  updatedAt: new Date('2026-08-01T00:00:00Z').toISOString(),
} as TransformationResponse;

function transformNode(data: Record<string, unknown> = {}): Node {
  return { id: 'node-1', type: 'transform', position: { x: 0, y: 0 }, data };
}

function renderPanel(node: Node, onUpdate = vi.fn()) {
  renderPage(<NodeConfigPanel node={node} onUpdate={onUpdate} onClose={vi.fn()} />, {
    path: '/projects/:projectId/workflows/:workflowId',
    initialEntry: `/projects/${TEST_PROJECT_ID}/workflows/workflow-1`,
  });
  return onUpdate;
}

const sourceToggle = () => screen.getByRole('group', { name: /where the shape|звідки береться/i });

describe('the transform node’s source', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(transformationsApi.list).mockResolvedValue([TRANSFORMATION]);
  });

  it('offers the project’s saved transformations by name', async () => {
    renderPanel(transformNode({ transformationId: TRANSFORMATION.id }));

    expect(await screen.findByRole('option', { name: /Flatten the customer/ })).toBeInTheDocument();
  });

  it('starts on the inline template when the node has no reference', async () => {
    renderPanel(transformNode({ template: '{"a":1}' }));

    await waitFor(() => expect(within(sourceToggle()).getAllByRole('button')
      .find((b) => b.getAttribute('aria-pressed') === 'true')?.textContent)
      .toMatch(/inline|на місці/i));
  });

  it('clears the reference when switching back to an inline template', async () => {
    // The executor runs the reference over any template left in the config, so a switch that
    // did not clear it would keep running the saved one while the panel showed the box.
    const onUpdate = renderPanel(transformNode({ transformationId: TRANSFORMATION.id }));
    await screen.findByRole('option', { name: /Flatten the customer/ });

    const inline = within(sourceToggle()).getAllByRole('button')
      .find((b) => /inline|на місці/i.test(b.textContent ?? ''))!;
    await userEvent.click(inline);

    expect(onUpdate).toHaveBeenCalledWith('node-1', expect.objectContaining({ transformationId: '' }));
  });

  it('selecting a transformation records its id on the node', async () => {
    const onUpdate = renderPanel(transformNode({ transformationId: 'x' }));
    const select = await screen.findByRole('combobox', { name: /transformation|трансформац/i });

    await userEvent.selectOptions(select, TRANSFORMATION.id);

    expect(onUpdate).toHaveBeenCalledWith('node-1', expect.objectContaining({
      transformationId: TRANSFORMATION.id,
    }));
  });

  it('creates a transformation without leaving the canvas, and selects it', async () => {
    vi.mocked(transformationsApi.create).mockResolvedValue({ ...TRANSFORMATION, id: 'transformation-new' });
    const onUpdate = renderPanel(transformNode({ transformationId: TRANSFORMATION.id }));

    await userEvent.click(await screen.findByRole('button', { name: /new transformation|нова трансформац/i }));
    // The accessible name carries the required marker, so it is "Name *", not "Name".
    await userEvent.type(screen.getByLabelText(/^(name|назва)\s*\*?$/i), 'Strip PII');
    await userEvent.click(screen.getByRole('button', { name: /^create$|^створити$/i }));

    await waitFor(() => expect(transformationsApi.create).toHaveBeenCalledWith(
      TEST_PROJECT_ID,
      expect.objectContaining({ name: 'Strip PII' }),
    ));
    // Created and left selected — otherwise the node still points at the old one.
    await waitFor(() => expect(onUpdate).toHaveBeenCalledWith('node-1', expect.objectContaining({
      transformationId: 'transformation-new',
    })));
  });

  it('creates nothing by being opened', async () => {
    renderPanel(transformNode({ transformationId: TRANSFORMATION.id }));

    await screen.findByRole('option', { name: /Flatten the customer/ });
    expect(transformationsApi.create).not.toHaveBeenCalled();
  });
});
