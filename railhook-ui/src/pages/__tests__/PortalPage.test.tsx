import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type {
  PageResponse, PortalDeliveryResponse, PortalEndpointResponse, PortalSessionInfoResponse,
} from '../../types/api.types';

vi.mock('../../api/portal.api', () => ({
  setPortalToken: vi.fn(),
  onPortalUnauthorized: vi.fn(),
  hasPortalToken: vi.fn(() => true),
  portalApi: {
    session: vi.fn(),
    listEndpoints: vi.fn(),
    createEndpoint: vi.fn(),
    updateEndpoint: vi.fn(),
    deleteEndpoint: vi.fn(),
    rotateSecret: vi.fn(),
    listDeliveries: vi.fn(),
    listAttempts: vi.fn(),
    retryDelivery: vi.fn(),
  },
}));

import PortalPage from '../PortalPage';
import { portalApi, setPortalToken } from '../../api/portal.api';

const SESSION: PortalSessionInfoResponse = {
  consumerName: 'Acme Ltd',
  projectName: 'Shop',
  expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  eventTypes: ['order.created', 'order.paid'],
};

const ENDPOINT: PortalEndpointResponse = {
  id: 'endpoint-1',
  url: 'https://acme.example.com/hooks',
  enabled: true,
  eventTypes: ['order.created'],
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const DELIVERY: PortalDeliveryResponse = {
  id: 'delivery-1',
  eventId: 'event-1',
  eventType: 'order.created',
  endpointId: ENDPOINT.id,
  status: 'FAILED',
  attemptCount: 7,
  maxAttempts: 7,
  createdAt: new Date().toISOString(),
};

function page<T>(items: T[]): PageResponse<T> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0, first: true, last: true } as any;
}

function openAt(url: string) {
  window.history.replaceState(null, '', url);
  return renderPage(<PortalPage />, { path: '/portal', initialEntry: '/portal' });
}

describe('PortalPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(portalApi.session).mockResolvedValue(SESSION);
    vi.mocked(portalApi.listEndpoints).mockResolvedValue([ENDPOINT]);
    vi.mocked(portalApi.listDeliveries).mockResolvedValue(page([DELIVERY]));
    vi.mocked(portalApi.listAttempts).mockResolvedValue([]);
  });

  afterEach(() => {
    window.history.replaceState(null, '', '/');
  });

  it('says the link is incomplete when it carries no session', async () => {
    openAt('/portal');
    expect(await screen.findByText(/this link is incomplete/i)).toBeInTheDocument();
    expect(portalApi.session).not.toHaveBeenCalled();
  });

  it('takes the token out of the address bar and keeps it only in memory', async () => {
    openAt('/portal#rhp_secret-token');
    expect(await screen.findByText('Acme Ltd · Shop')).toBeInTheDocument();
    expect(setPortalToken).toHaveBeenCalledWith('rhp_secret-token');
    expect(window.location.hash).toBe('');
    expect(window.location.href).not.toContain('rhp_');
    expect(window.localStorage.getItem('rhp_secret-token')).toBeNull();
    expect(JSON.stringify({ ...window.localStorage })).not.toContain('rhp_');
  });

  it('shows the ended-session screen when the session is no longer valid', async () => {
    vi.mocked(portalApi.session).mockRejectedValue({ response: { status: 401 } });
    openAt('/portal#rhp_expired');
    expect(await screen.findByText(/this session has ended/i)).toBeInTheDocument();
  });

  it('refuses to render for an origin that is not the session’s', async () => {
    vi.mocked(portalApi.session).mockResolvedValue({ ...SESSION, allowedOrigin: 'https://app.example.com' });
    openAt('/portal?origin=https://evil.example#rhp_token');
    expect(await screen.findByText(/can't be shown here/i)).toBeInTheDocument();
    expect(screen.queryByText(ENDPOINT.url)).not.toBeInTheDocument();
  });

  it('lists the consumer’s endpoints and registers a new one with its event types, showing the secret once', async () => {
    const user = userEvent.setup();
    vi.mocked(portalApi.createEndpoint).mockResolvedValue({
      ...ENDPOINT, id: 'endpoint-2', url: 'https://acme.example.com/new', eventTypes: ['order.paid'],
      secret: 'whsec-plain', standardWebhooksSecret: 'whsec_abc',
    });
    openAt('/portal#rhp_token');

    expect(await screen.findByText(ENDPOINT.url)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /add endpoint/i }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^url$/i), 'https://acme.example.com/new');
    await user.click(within(dialog).getByLabelText('order.paid'));
    await user.click(within(dialog).getByRole('button', { name: /add endpoint/i }));

    await waitFor(() => expect(portalApi.createEndpoint).toHaveBeenCalledWith({
      url: 'https://acme.example.com/new', description: '', enabled: true, eventTypes: ['order.paid'],
    }));
    expect(await screen.findByText(/your signing secret/i)).toBeInTheDocument();
    expect(screen.getAllByTestId('signing-secret')).toHaveLength(2);
  });

  it('shows deliveries with their attempts and retries a failed one', async () => {
    const user = userEvent.setup();
    vi.mocked(portalApi.listAttempts).mockResolvedValue([{
      id: 'attempt-1', deliveryId: DELIVERY.id, attemptNumber: 1, httpStatusCode: 500,
      responseBody: 'upstream down', createdAt: new Date().toISOString(),
    }]);
    vi.mocked(portalApi.retryDelivery).mockResolvedValue(undefined);
    openAt('/portal#rhp_token');

    await user.click(await screen.findByRole('tab', { name: /deliveries/i }));
    await user.click(await screen.findByRole('row', { name: /open delivery order\.created/i }));
    expect(await screen.findByText(/attempt #?1/i)).toBeInTheDocument();
    expect(screen.getAllByText('500').length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: /^retry$/i }));
    await waitFor(() => expect(portalApi.retryDelivery).toHaveBeenCalledWith(DELIVERY.id));
  });
});
