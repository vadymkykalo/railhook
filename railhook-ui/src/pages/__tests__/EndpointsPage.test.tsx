import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, EndpointResponse, PageResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: {
    list: vi.fn(),
    listPaged: vi.fn(),
    create: vi.fn(),
    delete: vi.fn(),
    update: vi.fn(),
    rotateSecret: vi.fn(),
    test: vi.fn(),
    verify: vi.fn(),
    skipVerification: vi.fn(),
  },
}));

import EndpointsPage from '../EndpointsPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';

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

function emptyPage<T>(): PageResponse<T> {
  return { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true } as any;
}

function populatedPage<T>(items: T[]): PageResponse<T> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0, first: true, last: true } as any;
}

function renderEndpoints() {
  return renderPage(<EndpointsPage />, {
    path: '/projects/:projectId/endpoints',
    initialEntry: `/projects/${TEST_PROJECT_ID}/endpoints`,
  });
}

describe('EndpointsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders a loading skeleton before data arrives', () => {
    vi.mocked(projectsApi.get).mockReturnValue(new Promise(() => {}));
    vi.mocked(endpointsApi.listPaged).mockReturnValue(new Promise(() => {}));
    const { container } = renderEndpoints();
    expect(container.querySelector('.animate-pulse')).toBeTruthy();
  });

  it('renders the onboarding empty state when the project genuinely has zero endpoints', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(endpointsApi.listPaged).mockResolvedValue(emptyPage());
    renderEndpoints();
    expect(await screen.findByText(/no endpoints yet/i)).toBeInTheDocument();
  });

  it('renders populated rows when endpoints exist', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(endpointsApi.listPaged).mockResolvedValue(populatedPage([ENDPOINT]));
    renderEndpoints();
    expect(await screen.findByText('https://example.com/webhook')).toBeInTheDocument();
  });

  it('has no detectable axe accessibility violations when populated', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(endpointsApi.listPaged).mockResolvedValue(populatedPage([ENDPOINT]));
    const { container } = renderEndpoints();
    await screen.findByText('https://example.com/webhook');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('renders an explicit error state — not the "create your first endpoint" empty state — when the API is down', async () => {
    vi.mocked(projectsApi.get).mockRejectedValue({ request: {}, message: 'Network Error' });
    vi.mocked(endpointsApi.listPaged).mockRejectedValue({ request: {}, message: 'Network Error' });
    renderEndpoints();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText(/no endpoints yet/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('recovers via the retry button without a full page reload', async () => {
    vi.mocked(projectsApi.get)
      .mockRejectedValueOnce({ request: {}, message: 'Network Error' })
      .mockResolvedValue(PROJECT);
    vi.mocked(endpointsApi.listPaged)
      .mockRejectedValueOnce({ request: {}, message: 'Network Error' })
      .mockResolvedValue(populatedPage([ENDPOINT]));

    const { default: userEvent } = await import('@testing-library/user-event');
    const user = userEvent.setup();
    renderEndpoints();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /retry/i }));

    expect(await screen.findByText('https://example.com/webhook')).toBeInTheDocument();
  });

  it('keyboard: Escape closes the create-endpoint dialog (Radix focus-return to the trigger verified manually in a real browser — jsdom has no layout engine, so Radix\'s focus-scope visibility check can\'t be exercised here)', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(endpointsApi.listPaged).mockResolvedValue(populatedPage([ENDPOINT]));

    const { default: userEvent } = await import('@testing-library/user-event');
    const user = userEvent.setup();
    renderEndpoints();

    await screen.findByText('https://example.com/webhook');

    const trigger = screen.getByRole('button', { name: /new endpoint/i });
    await user.click(trigger);

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeInTheDocument();
    // Radix auto-focuses the first focusable element inside the dialog on open.
    expect(dialog.contains(document.activeElement)).toBe(true);

    await user.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toBeInTheDocument();
  });

  it('lets the creator choose a signature scheme, and sends the one they chose', async () => {
    /* The picker was wired into the two Connection surfaces and not into this one, which is
       the flat sibling list — so an endpoint created here silently took the BOTH default and
       nobody was shown that there was a decision. The scheme decides which headers a
       receiver has to verify, which is the first thing their developer needs to know. */
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(endpointsApi.listPaged).mockResolvedValue(emptyPage());
    vi.mocked(endpointsApi.create).mockResolvedValue({ ...ENDPOINT, secret: 'whsec_x' } as any);

    renderEndpoints();
    await userEvent.click(await screen.findByRole('button', { name: /new endpoint/i }));

    await userEvent.type(screen.getByLabelText(/endpoint url/i), 'https://api.customer.com/hook');
    await userEvent.click(screen.getByRole('radio', { name: /standard webhooks only/i }));
    await userEvent.click(screen.getByRole('button', { name: /^create endpoint$/i }));

    await waitFor(() => expect(endpointsApi.create).toHaveBeenCalledWith(
      TEST_PROJECT_ID,
      expect.objectContaining({ signatureScheme: 'STANDARD' }),
    ));
  });
});
