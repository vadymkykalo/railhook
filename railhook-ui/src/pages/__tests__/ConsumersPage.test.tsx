import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ConsumerResponse, PageResponse, ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/consumers.api', () => ({
  consumersApi: {
    listPaged: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    listEndpoints: vi.fn(),
    createPortalSession: vi.fn(),
    revokePortalSessions: vi.fn(),
  },
}));

import ConsumersPage from '../ConsumersPage';
import { projectsApi } from '../../api/projects.api';
import { consumersApi } from '../../api/consumers.api';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Shop',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const CONSUMER: ConsumerResponse = {
  id: 'consumer-1',
  projectId: TEST_PROJECT_ID,
  externalId: 'user_42',
  name: 'Acme Ltd',
  endpointCount: 2,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const SESSION = {
  id: 'session-1',
  consumerId: CONSUMER.id,
  url: 'https://railhook.example.com/portal?origin=https://app.example.com#rhp_abc',
  token: 'rhp_abc',
  allowedOrigin: 'https://app.example.com',
  expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
};

function page<T>(items: T[]): PageResponse<T> {
  return { content: items, totalElements: items.length, totalPages: items.length ? 1 : 0, size: 20, number: 0, first: true, last: true } as any;
}

function renderConsumers() {
  return renderPage(<ConsumersPage />, {
    path: '/projects/:projectId/consumers',
    initialEntry: `/projects/${TEST_PROJECT_ID}/consumers`,
  });
}

describe('ConsumersPage', () => {
  const originalOpen = window.open;

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
  });

  afterEach(() => {
    window.open = originalOpen;
  });

  it('explains what a consumer is when the project has none', async () => {
    vi.mocked(consumersApi.listPaged).mockResolvedValue(page([]));
    renderConsumers();
    expect(await screen.findByText(/no consumers yet/i)).toBeInTheDocument();
  });

  it('creates a consumer from its external id and name', async () => {
    const user = userEvent.setup();
    vi.mocked(consumersApi.listPaged).mockResolvedValue(page([]));
    vi.mocked(consumersApi.create).mockResolvedValue(CONSUMER);
    renderConsumers();

    await user.click(await screen.findByRole('button', { name: /new consumer/i }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/external id/i), 'user_42');
    await user.type(within(dialog).getByLabelText(/^name$/i), 'Acme Ltd');
    await user.click(within(dialog).getByRole('button', { name: /create/i }));

    await waitFor(() => expect(consumersApi.create).toHaveBeenCalledWith(TEST_PROJECT_ID, {
      externalId: 'user_42', name: 'Acme Ltd',
    }));
  });

  it('lists consumers with their endpoints', async () => {
    const user = userEvent.setup();
    vi.mocked(consumersApi.listPaged).mockResolvedValue(page([CONSUMER]));
    vi.mocked(consumersApi.listEndpoints).mockResolvedValue([{
      id: 'endpoint-1', projectId: TEST_PROJECT_ID, consumerId: CONSUMER.id, url: 'https://acme.example.com/hooks',
      enabled: true, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }]);
    renderConsumers();

    expect(await screen.findByText('Acme Ltd')).toBeInTheDocument();
    expect(screen.getByText('user_42')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /2 endpoints/i }));
    expect(await screen.findByText('https://acme.example.com/hooks')).toBeInTheDocument();
  });

  it('opens the portal in a new tab through a fresh session', async () => {
    const user = userEvent.setup();
    const tab = { opener: {}, location: { href: '' }, close: vi.fn() };
    window.open = vi.fn(() => tab as unknown as Window);
    vi.mocked(consumersApi.listPaged).mockResolvedValue(page([CONSUMER]));
    vi.mocked(consumersApi.createPortalSession).mockResolvedValue(SESSION);
    renderConsumers();

    await user.click(await screen.findByRole('button', { name: /open portal/i }));

    await waitFor(() => expect(tab.location.href).toBe(SESSION.url));
    expect(consumersApi.createPortalSession).toHaveBeenCalledWith(TEST_PROJECT_ID, CONSUMER.id, undefined);
    expect(tab.opener).toBeNull();
  });

  it('gives an iframe snippet for a session bound to the embedding origin', async () => {
    const user = userEvent.setup();
    vi.mocked(consumersApi.listPaged).mockResolvedValue(page([CONSUMER]));
    vi.mocked(consumersApi.createPortalSession).mockResolvedValue(SESSION);
    renderConsumers();

    await user.click(await screen.findByRole('button', { name: /embed snippet/i }));
    const dialog = await screen.findByRole('dialog');
    const origin = within(dialog).getByLabelText(/allowed origin/i);

    await user.type(origin, 'http://app.example.com');
    expect(within(dialog).getByRole('button', { name: /create session/i })).toBeDisabled();

    await user.clear(origin);
    await user.type(origin, 'https://app.example.com');
    await user.click(within(dialog).getByRole('button', { name: /create session/i }));

    await waitFor(() => expect(consumersApi.createPortalSession).toHaveBeenCalledWith(
      TEST_PROJECT_ID, CONSUMER.id, { allowedOrigin: 'https://app.example.com' },
    ));
    expect(await within(dialog).findByText(/<iframe/)).toHaveTextContent(SESSION.url);
  });
});
