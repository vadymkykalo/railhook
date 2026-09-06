import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { list: vi.fn(), get: vi.fn() },
}));
vi.mock('../../api/auth.api', () => ({
  authApi: { getCurrentUser: vi.fn().mockResolvedValue(null), logout: vi.fn(), resendVerification: vi.fn() },
}));

import AppLayout from '../../layout/AppLayout';
import { projectsApi } from '../../api/projects.api';

const project = (id: string, name: string): ProjectResponse => ({
  id, name,
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
});

function renderAt(path: string) {
  return renderPage(<AppLayout />, { path: '/admin/*', initialEntry: path });
}

/**
 * The rail's href only settles once `useProjects` resolves, and waitFor's
 * default second is not enough for that on a loaded CI runner — this failed at
 * 1313ms there while passing locally every time. Same reasoning as
 * DashboardPage's SETTLE_MS: room for a slow machine, no cover for a wrong
 * render, because a bad href still fails on the first poll after settling.
 */
const SETTLE_MS = 8_000;

beforeEach(() => vi.clearAllMocks());

describe('the rail without a project in the URL', () => {
  it('still points every entry at a real project', async () => {
    // On /admin/projects there is no :projectId, and nav.config used to fall
    // back to '/admin/projects' — the page you are already on. Every rail entry
    // was a link that changed nothing, which reads as a broken button.
    vi.mocked(projectsApi.list).mockResolvedValue([project(TEST_PROJECT_ID, 'Production')]);
    renderAt('/admin/projects');

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /Deliveries/i }))
        .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/deliveries`), { timeout: SETTLE_MS });
  });

  it('names the project it fell back to instead of asking you to pick one', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([project(TEST_PROJECT_ID, 'Production')]);
    renderAt('/admin/projects');

    expect(await screen.findByText('Production', undefined, { timeout: SETTLE_MS })).toBeInTheDocument();
    expect(screen.queryByText(/Select project/i)).toBeNull();
  });

  it('sends you to make one when the account has none', async () => {
    // With nothing to fall back to, '/admin/projects' is the honest destination.
    vi.mocked(projectsApi.list).mockResolvedValue([]);
    renderAt('/admin/dashboard');

    const deliveries = await screen.findByRole('link', { name: /Deliveries/i });
    expect(deliveries).toHaveAttribute('href', '/admin/projects');
  });

  it('leaves the project in the URL alone', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([
      project('other-project', 'Staging'),
      project(TEST_PROJECT_ID, 'Production'),
    ]);
    renderAt(`/admin/projects/${TEST_PROJECT_ID}/deliveries`);

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /^Events/i }))
        .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/events`), { timeout: SETTLE_MS });
  });
});
