import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, PageResponse, DeliveryResponse } from '../../types/api.types';
import type { DashboardStats } from '../../api/dashboard.api';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { list: vi.fn(), get: vi.fn() },
}));
vi.mock('../../api/dashboard.api', () => ({
  dashboardApi: {
    getProjectStats: vi.fn(),
    getAnalytics: vi.fn(),
    getOnboardingStatus: vi.fn(),
  },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn() },
}));
vi.mock('../../api/deliveries.api', () => ({
  deliveriesApi: { listByProject: vi.fn() },
}));
vi.mock('../../api/alerts.api', () => ({
  alertsApi: { unresolvedCount: vi.fn() },
}));
vi.mock('../../api/incidents.api', () => ({
  incidentsApi: { countOpen: vi.fn() },
}));
vi.mock('../../api/incomingSources.api', () => ({
  incomingSourcesApi: { list: vi.fn() },
}));

import DashboardPage from '../DashboardPage';
import { projectsApi } from '../../api/projects.api';
import { dashboardApi } from '../../api/dashboard.api';
import { endpointsApi } from '../../api/endpoints.api';
import { deliveriesApi } from '../../api/deliveries.api';
import { alertsApi } from '../../api/alerts.api';
import { incidentsApi } from '../../api/incidents.api';
import { incomingSourcesApi } from '../../api/incomingSources.api';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const STATS: DashboardStats = {
  deliveryStats: {
    totalDeliveries: 42,
    successfulDeliveries: 40,
    failedDeliveries: 1,
    pendingDeliveries: 1,
    dlqDeliveries: 0,
    successRate: 95,
  },
  recentEvents: [],
  endpointHealth: [],
};

const EMPTY_PAGE: PageResponse<DeliveryResponse> = {
  content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true,
};

/** Seven queries plus Recharts settle slowly under parallel files; a wrong render still fails fast. */
const SETTLE_MS = 8_000;
const TEST_TIMEOUT_MS = 20_000;

function renderDashboard() {
  return renderPage(<DashboardPage />, {
    path: '/',
    initialEntry: '/',
  });
}

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(endpointsApi.list).mockResolvedValue([]);
    vi.mocked(dashboardApi.getAnalytics).mockResolvedValue({} as any);
    vi.mocked(dashboardApi.getOnboardingStatus).mockResolvedValue({
      hasEndpoints: false, hasSubscriptions: false, hasApiKeys: false, hasEvents: false,
      hasDeliveries: false, hasIncomingSources: false, hasIncomingDestinations: false,
    });
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(EMPTY_PAGE);
    vi.mocked(alertsApi.unresolvedCount).mockResolvedValue({ count: 0 });
    vi.mocked(incidentsApi.countOpen).mockResolvedValue({ count: 0, investigating: 0, critical: 0 });
    vi.mocked(incomingSourcesApi.list).mockResolvedValue({
      content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true,
    });
    localStorage.clear();
  });

  it('opens nothing over the dashboard on a first visit', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT]);
    vi.mocked(dashboardApi.getProjectStats).mockResolvedValue(STATS);
    renderDashboard();
    await screen.findByText(/Getting started/i, undefined, { timeout: SETTLE_MS });
    expect(screen.queryByRole('dialog')).toBeNull();
  }, TEST_TIMEOUT_MS);

  it('opens on the project you were last in, not the first one', async () => {
    const OTHER: ProjectResponse = { ...PROJECT, id: 'project-other', name: 'Load test' };
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT, OTHER]);
    vi.mocked(dashboardApi.getProjectStats).mockResolvedValue(STATS);
    localStorage.setItem('railhook:last-project', OTHER.id);

    renderDashboard();

    await waitFor(() => expect(dashboardApi.getProjectStats).toHaveBeenCalledWith(OTHER.id), { timeout: SETTLE_MS });
    expect(dashboardApi.getProjectStats).not.toHaveBeenCalledWith(PROJECT.id);
  }, TEST_TIMEOUT_MS);

  it('starts an organization with no project on the first step, with one call to action', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([]);
    renderDashboard();
    expect(await screen.findByText('Create a project')).toBeInTheDocument();
    expect(screen.getByText('Create an API key')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /create project/i })).toHaveLength(1);
  });

  it('creates the first project from the dashboard without leaving it', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([]);
    const user = userEvent.setup();
    renderDashboard();

    await user.click(await screen.findByRole('button', { name: /create project/i }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByLabelText(/project name/i)).toBeInTheDocument();
  });

  it('offers a way back after the getting-started card is dismissed', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT]);
    vi.mocked(dashboardApi.getProjectStats).mockResolvedValue(STATS);
    const user = userEvent.setup();
    renderDashboard();

    await user.click(await screen.findByRole('button', { name: 'Dismiss' }, { timeout: SETTLE_MS }));
    const restore = await screen.findByRole('button', { name: /Getting started/ });

    await user.click(restore);
    expect(await screen.findByText('What brings you to Railhook?')).toBeInTheDocument();
  }, TEST_TIMEOUT_MS);

  it('renders a loading skeleton before the project list arrives', () => {
    vi.mocked(projectsApi.list).mockReturnValue(new Promise(() => {}));
    const { container } = renderDashboard();
    expect(container.querySelector('.animate-pulse')).toBeTruthy();
  });


  it('renders populated stat cards when a project with data exists', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT]);
    vi.mocked(dashboardApi.getProjectStats).mockResolvedValue(STATS);
    renderDashboard();
    expect(await screen.findByText('42', undefined, { timeout: SETTLE_MS })).toBeInTheDocument();
  }, TEST_TIMEOUT_MS);

  it('has no detectable axe accessibility violations when populated', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT]);
    vi.mocked(dashboardApi.getProjectStats).mockResolvedValue(STATS);
    const { container } = renderDashboard();
    await screen.findByText('42', undefined, { timeout: SETTLE_MS });
    expect(await axe(container)).toHaveNoViolations();
  }, TEST_TIMEOUT_MS);

  it('renders an explicit error state — not "no projects yet" — when the API is unreachable', async () => {
    vi.mocked(projectsApi.list).mockRejectedValue({ request: {}, message: 'Network Error' });
    renderDashboard();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText(/no projects yet/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('renders an error state, not an empty account, when the stats request itself fails', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT]);
    vi.mocked(dashboardApi.getProjectStats).mockRejectedValue({ request: {}, message: 'Network Error' });
    renderDashboard();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('renders a project whose stats payload is missing deliveryStats entirely', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT]);
    vi.mocked(dashboardApi.getProjectStats).mockResolvedValue({} as unknown as DashboardStats);
    renderDashboard();

    await waitFor(() => expect(screen.getByTestId('delivery-health-figure')).toHaveTextContent('—'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('renders a brand-new project whose every counter is zero', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT]);
    vi.mocked(dashboardApi.getProjectStats).mockResolvedValue({
      deliveryStats: {
        totalDeliveries: 0, successfulDeliveries: 0, failedDeliveries: 0,
        pendingDeliveries: 0, dlqDeliveries: 0, successRate: 0,
      },
      recentEvents: [],
      endpointHealth: [],
    });
    renderDashboard();

    await waitFor(() => expect(screen.getByTestId('delivery-health-figure')).toHaveTextContent('—'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
