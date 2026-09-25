import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, EndpointResponse } from '../../types/api.types';

// Regression: a project name piped through raw-HTML injection became a live <img>; <Trans> escapes it.

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn(), listPaged: vi.fn() },
}));
vi.mock('../../api/deliveries.api', () => ({
  deliveriesApi: {
    listByProject: vi.fn(),
    get: vi.fn(),
    replay: vi.fn(),
    dryRunReplay: vi.fn(),
    replayFromAttempt: vi.fn(),
    bulkReplay: vi.fn(),
    getAttempts: vi.fn(),
  },
}));
vi.mock('../../api/events.api', () => ({
  eventsApi: { get: vi.fn(), listByProject: vi.fn(), sendTestEvent: vi.fn() },
}));

import DeliveriesPage from '../DeliveriesPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { deliveriesApi } from '../../api/deliveries.api';

const HOSTILE_NAME = '<img src=x onerror=alert(1)>';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: HOSTILE_NAME,
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1',
  projectId: TEST_PROJECT_ID,
  url: 'https://example.com/webhook',
  enabled: true,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

function emptyPage() {
  return { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 } as any;
}

function renderDeliveries() {
  return renderPage(<DeliveriesPage />, {
    path: '/projects/:projectId/deliveries',
    initialEntry: `/projects/${TEST_PROJECT_ID}/deliveries`,
  });
}

describe('DeliveriesPage XSS', () => {
  let alertSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(emptyPage());
    alertSpy = vi.fn();
    window.alert = alertSpy as unknown as typeof window.alert;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('canonical case: a hostile project name does not execute as script', async () => {
    const { container } = renderDeliveries();

    await screen.findByText(/onerror=alert/);

    expect(container.querySelector('img')).toBeNull();
    expect(alertSpy).not.toHaveBeenCalled();
  });

  it('the hostile name pushed through the <Trans> subtitle renders as literal text', async () => {
    renderDeliveries();

    const strongEl = await screen.findByText(/onerror=alert/);
    expect(strongEl).toBeInTheDocument();
    expect(strongEl.tagName.toLowerCase()).toBe('strong');
    expect(strongEl.textContent).toContain('img src=x onerror=alert(1)>');
    expect(strongEl.querySelector('img')).toBeNull();
    expect(document.querySelectorAll('img').length).toBe(0);
    expect(alertSpy).not.toHaveBeenCalled();
  });
});
