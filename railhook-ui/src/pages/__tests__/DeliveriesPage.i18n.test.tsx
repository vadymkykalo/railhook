import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest';
import { screen } from '@testing-library/react';
import i18n from '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, EndpointResponse } from '../../types/api.types';

// Regression test: DeliveriesPage used to render its status-filter
// options and status badges as raw hardcoded English (`{ value: '', label:
// 'All Statuses' }`, `{status}`) instead of going through i18n — so switching
// the dashboard to Ukrainian left this page half-translated. This asserts a
// status badge and a filter option are actually translated in both locales.

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

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
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

function populatedPage(items: any[]) {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0 } as any;
}

const DELIVERY = {
  id: 'delivery-1',
  eventId: 'event-1',
  endpointId: ENDPOINT.id,
  subscriptionId: 'sub-1',
  status: 'SUCCESS' as const,
  attemptCount: 1,
  maxAttempts: 5,
  createdAt: new Date().toISOString(),
};

function renderDeliveries() {
  return renderPage(<DeliveriesPage />, {
    path: '/projects/:projectId/deliveries',
    initialEntry: `/projects/${TEST_PROJECT_ID}/deliveries`,
  });
}

describe('DeliveriesPage i18n', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(populatedPage([DELIVERY]));
  });

  afterAll(async () => {
    await i18n.changeLanguage('en');
  });

  it('renders the status badge and the "All Statuses" filter option in English', async () => {
    await i18n.changeLanguage('en');
    renderDeliveries();

    expect(await screen.findByText('Success')).toBeInTheDocument();
    // The status <Select> is a closed Radix combobox in tests — its trigger
    // shows the currently selected option's translated label ("" -> "All
    // Statuses" / "Всі статуси"), which is what a user actually sees without
    // opening the dropdown.
    expect(screen.getByRole('combobox', { name: /status/i })).toHaveTextContent('All Statuses');
  });

  it('renders the status badge and the status filter option translated in Ukrainian', async () => {
    await i18n.changeLanguage('uk');
    renderDeliveries();

    expect(await screen.findByText('Успіх')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: /статус/i })).toHaveTextContent('Всі статуси');
    // The raw English default value must not leak through when translated.
    expect(screen.queryByText('SUCCESS')).not.toBeInTheDocument();
  });
});
