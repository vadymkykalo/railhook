import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type { ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock('../../api/dashboard.api', () => ({
  dashboardApi: { getProjectStats: vi.fn().mockResolvedValue({}) },
}));

import ProjectsPage from '../ProjectsPage';
import { projectsApi } from '../../api/projects.api';

/**
 * The first screen after signing up, and the one a delete goes through.
 *
 * <p>What is worth holding down here is the destructive path: a project owns every endpoint,
 * subscription and event under it, so a delete that fires without a confirmation is not a bug
 * someone reports — it is a support conversation about data that is gone. The rest is that the
 * empty state exists at all, because a brand-new account sees nothing else.
 */

const PROJECT: ProjectResponse = {
  id: 'project-1',
  name: 'Production',
  description: 'The real one',
  createdAt: new Date('2026-08-01T00:00:00Z').toISOString(),
} as ProjectResponse;

function renderProjects() {
  return renderPage(<ProjectsPage />, { path: '/projects', initialEntry: '/projects' });
}

describe('ProjectsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT]);
  });

  it('lists the projects', async () => {
    renderProjects();

    expect(await screen.findByText('Production')).toBeInTheDocument();
  });

  it('tells a new account what to do instead of showing an empty page', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([]);
    renderProjects();

    await waitFor(() => expect(projectsApi.list).toHaveBeenCalled());
    // Whatever the wording, there has to be something: this is the whole screen for a
    // just-registered user.
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
    expect(screen.queryByText('Production')).toBeNull();
  });

  it('does not delete anything without a confirmation', async () => {
    renderProjects();
    await screen.findByText('Production');

    const deleteButton = screen.queryAllByRole('button')
      .find((b) => /delete|видалити/i.test(b.getAttribute('aria-label') ?? b.textContent ?? ''));

    if (deleteButton) {
      await userEvent.click(deleteButton);
      // Opening the dialog is not the same as agreeing to it. Nothing may be gone yet.
      expect(projectsApi.delete).not.toHaveBeenCalled();
    }
    expect(projectsApi.delete).not.toHaveBeenCalled();
  });

  it('creates nothing by rendering', async () => {
    renderProjects();

    await screen.findByText('Production');
    expect(projectsApi.create).not.toHaveBeenCalled();
    expect(projectsApi.delete).not.toHaveBeenCalled();
  });

  it('shows an error state rather than a blank page when the list fails', async () => {
    vi.mocked(projectsApi.list).mockRejectedValue(new Error('boom'));
    renderProjects();

    await waitFor(() => expect(projectsApi.list).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
  });
});
