import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { EventTypeCatalogResponse } from '../../api/schemas.api';
import type { ProjectResponse } from '../../types/api.types';

vi.mock('../../api/schemas.api', () => ({
  schemasApi: {
    listEventTypes: vi.fn(),
    createEventType: vi.fn(),
    getEventType: vi.fn(),
    updateEventType: vi.fn(),
    deleteEventType: vi.fn(),
    listVersions: vi.fn().mockResolvedValue([]),
    createVersion: vi.fn(),
    getVersion: vi.fn(),
    promoteVersion: vi.fn(),
    deprecateVersion: vi.fn(),
    listChanges: vi.fn().mockResolvedValue([]),
    listProjectChanges: vi.fn().mockResolvedValue([]),
  },
}));

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn(), update: vi.fn() },
}));

import SchemasPage from '../SchemasPage';
import { schemasApi } from '../../api/schemas.api';
import { projectsApi } from '../../api/projects.api';

const now = new Date('2026-08-01T00:00:00Z').toISOString();

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  description: 'The real one',
  schemaValidationEnabled: true,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: now,
  updatedAt: now,
} as ProjectResponse;

const EVENT_TYPE: EventTypeCatalogResponse = {
  id: 'type-1',
  projectId: TEST_PROJECT_ID,
  name: 'payment.succeeded',
  description: null,
  latestVersion: 3,
  activeVersionStatus: 'ACTIVE',
  hasBreakingChanges: false,
  createdAt: now,
  updatedAt: now,
};

function renderSchemas() {
  return renderPage(<SchemasPage />, {
    path: '/projects/:projectId/schemas',
    initialEntry: `/projects/${TEST_PROJECT_ID}/schemas`,
  });
}

function choiceGroup(name: RegExp): HTMLElement {
  return screen.getByRole('group', { name });
}

function segments(group: HTMLElement): HTMLElement[] {
  return within(group).getAllByRole('button');
}

function selected(group: HTMLElement): HTMLElement | undefined {
  return segments(group).find((b) => b.getAttribute('aria-pressed') === 'true');
}

describe('SchemasPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(projectsApi.update).mockResolvedValue(PROJECT);
    vi.mocked(schemasApi.listEventTypes).mockResolvedValue([EVENT_TYPE]);
  });

  it('lists the event types that have a contract', async () => {
    renderSchemas();

    expect(await screen.findByText('payment.succeeded')).toBeInTheDocument();
  });

  it('shows the validation setting the project actually has', async () => {
    renderSchemas();

    const group = await waitFor(() => choiceGroup(/validation|валідац/i));
    const chosen = selected(group);
    expect(chosen?.textContent).toMatch(/warn|попередж/i);
  });

  it('turning validation up to BLOCK sends BLOCK and nothing else', async () => {
    renderSchemas();
    const group = await waitFor(() => choiceGroup(/validation|валідац/i));

    const block = segments(group).find((b) => /block|блок/i.test(b.textContent ?? ''))!;
    await userEvent.click(block);

    await waitFor(() => expect(projectsApi.update).toHaveBeenCalledWith(
      TEST_PROJECT_ID,
      expect.objectContaining({ schemaValidationEnabled: true, schemaValidationPolicy: 'BLOCK' }),
    ));
    expect(vi.mocked(projectsApi.update).mock.calls[0][1]).not.toHaveProperty('idempotencyPolicy');
  });

  it('changing the idempotency policy does not restate the validation settings', async () => {
    // The API leaves a null field alone, so omitting it keeps this a one-setting change.
    renderSchemas();
    const group = await waitFor(() => choiceGroup(/idempot|ідемпот/i));

    const required = segments(group).find((b) => /required|обов/i.test(b.textContent ?? ''))!;
    await userEvent.click(required);

    await waitFor(() => expect(projectsApi.update).toHaveBeenCalled());
    const body = vi.mocked(projectsApi.update).mock.calls[0][1] as unknown as Record<string, unknown>;
    expect(body).toMatchObject({ idempotencyPolicy: 'REQUIRED' });
    expect(body).not.toHaveProperty('schemaValidationEnabled');
    expect(body).not.toHaveProperty('schemaValidationPolicy');
  });

  it('shows an error state rather than an empty catalogue when the load fails', async () => {
    vi.mocked(schemasApi.listEventTypes).mockRejectedValue(new Error('boom'));
    renderSchemas();

    await waitFor(() => expect(schemasApi.listEventTypes).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent).not.toMatch(/\b0\b/));
  });

  it('changes no setting and creates no schema by being opened', async () => {
    renderSchemas();

    await screen.findByText('payment.succeeded');
    expect(projectsApi.update).not.toHaveBeenCalled();
    expect(schemasApi.createEventType).not.toHaveBeenCalled();
    expect(schemasApi.createVersion).not.toHaveBeenCalled();
    expect(schemasApi.deleteEventType).not.toHaveBeenCalled();
  });
});
