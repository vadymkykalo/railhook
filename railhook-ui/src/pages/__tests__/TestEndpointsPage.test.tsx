import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, fireEvent, screen } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { CapturedRequestResponse, PageResponse, TestEndpointResponse } from '../../api/testEndpoints.api';

vi.mock('../../api/testEndpoints.api', () => ({
  testEndpointsApi: { list: vi.fn(), getRequests: vi.fn(), create: vi.fn(), delete: vi.fn(), clearRequests: vi.fn() },
}));

import TestEndpointsPage from '../TestEndpointsPage';
import { testEndpointsApi } from '../../api/testEndpoints.api';

const later = new Date(Date.now() + 3_600_000).toISOString();
const endpoint = (slug: string): TestEndpointResponse => ({
  id: slug, projectId: TEST_PROJECT_ID, slug, url: `https://t.example.com/${slug}`,
  createdAt: new Date().toISOString(), expiresAt: later, requestCount: 1,
});
const captured = (endpointId: string, method: string): PageResponse<CapturedRequestResponse> => ({
  content: [{ id: `${endpointId}-r`, testEndpointId: endpointId, method, receivedAt: new Date().toISOString() }],
  totalElements: 1, totalPages: 1, size: 20, number: 0,
} as PageResponse<CapturedRequestResponse>);

describe('TestEndpointsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows the selected endpoint's requests even when an earlier selection answers last", async () => {
    let answerFirst: (v: PageResponse<CapturedRequestResponse>) => void = () => {};
    vi.mocked(testEndpointsApi.list).mockResolvedValue([endpoint('alpha'), endpoint('beta')]);
    vi.mocked(testEndpointsApi.getRequests).mockImplementation((_p, id) =>
      id === 'alpha'
        ? new Promise((resolve) => { answerFirst = resolve; })
        : Promise.resolve(captured('beta', 'PUT')));
    renderPage(<TestEndpointsPage />, {
      path: '/projects/:projectId/test-endpoints',
      initialEntry: `/projects/${TEST_PROJECT_ID}/test-endpoints`,
    });

    fireEvent.click(await screen.findByText('alpha'));
    fireEvent.click(screen.getByText('beta'));
    expect(await screen.findByText('PUT')).toBeInTheDocument();

    await act(async () => { answerFirst(captured('alpha', 'DELETE')); });

    expect(screen.getByText('PUT')).toBeInTheDocument();
    expect(screen.queryByText('DELETE')).not.toBeInTheDocument();
  });
});
