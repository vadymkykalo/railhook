import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type { ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
}));

import ProjectSetupPage from '../ProjectSetupPage';
import { projectsApi } from '../../api/projects.api';

const CREATED = {
  id: 'project-new',
  name: 'Checkout',
  createdAt: new Date('2026-09-13T00:00:00Z').toISOString(),
} as ProjectResponse;

function Where() {
  const location = useLocation();
  return <p data-testid="where">{location.pathname}</p>;
}

function renderSetup(segment: string) {
  return renderPage(
    <Routes>
      <Route path="/admin/start/:segment" element={<ProjectSetupPage />} />
      <Route path="*" element={<Where />} />
    </Routes>,
    { path: '*', initialEntry: `/admin/start/${segment}` },
  );
}

describe('ProjectSetupPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.list).mockResolvedValue([]);
    vi.mocked(projectsApi.create).mockResolvedValue(CREATED);
  });

  it('says what the section is for and offers to create a project', async () => {
    renderSetup('events');

    expect(await screen.findByRole('heading', { name: 'Events' })).toBeInTheDocument();
    expect(screen.getByText(/every event your application sends/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create project/i })).toBeInTheDocument();
  });

  it('creates the project and continues to the section that was clicked', async () => {
    const user = userEvent.setup();
    renderSetup('deliveries');

    await user.click(await screen.findByRole('button', { name: /create project/i }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/project name/i), 'Checkout');
    await user.click(within(dialog).getByRole('button', { name: /^create project$/i }));

    await waitFor(() => expect(projectsApi.create).toHaveBeenCalledWith({ name: 'Checkout', description: '' }));
    expect(await screen.findByTestId('where')).toHaveTextContent('/admin/projects/project-new/deliveries');
  });

  it('goes straight to the section when the organization already has a project', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([CREATED]);
    renderSetup('analytics');

    expect(await screen.findByTestId('where')).toHaveTextContent('/admin/projects/project-new/analytics');
  });
});
