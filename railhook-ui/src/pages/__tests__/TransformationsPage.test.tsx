import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, TransformationResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/transformations.api', () => ({
  transformationsApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
}));
vi.mock('../../api/transform.api', () => ({
  transformApi: { preview: vi.fn(), deliveryDryRun: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../api/events.api', () => ({
  eventsApi: { listByProject: vi.fn().mockResolvedValue({ content: [], last: true }) },
}));

import TransformationsPage from '../TransformationsPage';
import TransformStudioPage from '../TransformStudioPage';
import { projectsApi } from '../../api/projects.api';
import { transformationsApi } from '../../api/transformations.api';

const NOW = new Date().toISOString();

const TRANSFORMATION = {
  id: 'tr-1', projectId: TEST_PROJECT_ID, name: 'Slack shape', description: 'For Slack', version: 2,
  template: '{"text":"${$.type}"}', enabled: true, subscriptionCount: 1, destinationCount: 0,
  createdAt: NOW, updatedAt: NOW,
} as TransformationResponse;

/**
 * Transform Studio was a Develop tab of its own, so there were two places to work on a
 * transformation and nothing linking them. The studio is now where a transformation is tried
 * against a real event, opened from the transformation itself.
 */
describe('Transformations and the studio', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue({ id: TEST_PROJECT_ID, name: 'P' } as ProjectResponse);
    vi.mocked(transformationsApi.list).mockResolvedValue([TRANSFORMATION]);
  });

  it('opens the studio from the page, and from each transformation with it loaded', async () => {
    renderPage(<TransformationsPage />, {
      path: '/admin/projects/:projectId/transformations',
      initialEntry: `/admin/projects/${TEST_PROJECT_ID}/transformations`,
    });

    expect(await screen.findByRole('link', { name: 'Open the studio' }))
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/transformations/studio`);
    const row = screen.getByText('Slack shape').closest('tr')!;
    expect(within(row).getByRole('link', { name: 'Try on an event' }))
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/transformations/studio?transformationId=tr-1`);
  });

  it('arrives in the studio with the transformation it was opened for, under Transformations', async () => {
    renderPage(<TransformStudioPage />, {
      path: '/admin/projects/:projectId/transformations/studio',
      initialEntry: `/admin/projects/${TEST_PROJECT_ID}/transformations/studio?transformationId=tr-1`,
    });

    const crumbs = await screen.findByRole('navigation', { name: /breadcrumb/i });
    expect(within(crumbs).getAllByRole('link', { name: 'Transformations' })[0])
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/transformations`);
    const select = await screen.findByLabelText('Saved transformation');
    await waitFor(() => expect(select).toHaveTextContent('Slack shape v2'));
  });
});
