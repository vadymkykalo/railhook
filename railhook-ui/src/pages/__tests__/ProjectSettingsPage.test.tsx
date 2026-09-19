import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn(), update: vi.fn() },
}));

import ProjectSettingsPage from '../ProjectSettingsPage';
import { projectsApi } from '../../api/projects.api';

const now = new Date('2026-09-01T00:00:00Z').toISOString();

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Checkout',
  description: 'Payments and orders',
  schemaValidationEnabled: true,
  schemaValidationPolicy: 'BLOCK',
  idempotencyPolicy: 'AUTO',
  createdAt: now,
  updatedAt: now,
} as ProjectResponse;

function renderSettings() {
  return renderPage(<ProjectSettingsPage />, {
    path: '/admin/projects/:projectId/project-settings',
    initialEntry: `/admin/projects/${TEST_PROJECT_ID}/project-settings`,
  });
}

/**
 * The idempotency policy decides whether a second event with the same key is dropped, so it
 * answers "why did my event vanish" — and it sat on the Schemas page, which is about event
 * shapes. It is a property of the project, and lives with the project's other settings now.
 */
describe('ProjectSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(projectsApi.update).mockResolvedValue(PROJECT);
  });

  it('is headed by the words of its tab, over the project it belongs to', async () => {
    renderSettings();

    expect(await screen.findByRole('heading', { level: 1, name: 'Project' })).toBeInTheDocument();
    expect(screen.getByDisplayValue('Checkout')).toBeInTheDocument();
  });

  it('shows the idempotency policy the project actually has', async () => {
    renderSettings();

    const group = await screen.findByRole('group', { name: /idempotency/i });
    const pressed = within(group).getAllByRole('button').find((b) => b.getAttribute('aria-pressed') === 'true');
    expect(pressed).toHaveTextContent('Auto');
  });

  it('changes the idempotency policy without restating the validation settings', async () => {
    // The API leaves a null field alone, so leaving them out is how this stays a one-setting
    // change; sending a stale copy back is how one panel silently undoes another.
    renderSettings();
    const group = await screen.findByRole('group', { name: /idempotency/i });

    await userEvent.click(within(group).getByRole('button', { name: 'Required' }));

    await waitFor(() => expect(projectsApi.update).toHaveBeenCalled());
    const body = vi.mocked(projectsApi.update).mock.calls[0][1] as unknown as Record<string, unknown>;
    expect(body).toMatchObject({ name: 'Checkout', idempotencyPolicy: 'REQUIRED' });
    expect(body).not.toHaveProperty('schemaValidationEnabled');
    expect(body).not.toHaveProperty('schemaValidationPolicy');
  });

  it('renames the project', async () => {
    renderSettings();
    const name = await screen.findByLabelText('Project name');

    await userEvent.clear(name);
    await userEvent.type(name, 'Checkout EU');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(projectsApi.update).toHaveBeenCalledWith(
      TEST_PROJECT_ID,
      { name: 'Checkout EU', description: 'Payments and orders' },
    ));
  });
});
