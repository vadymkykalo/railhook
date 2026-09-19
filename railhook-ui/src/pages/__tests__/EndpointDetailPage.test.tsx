import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { Route, Routes, useLocation } from 'react-router-dom';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { DeliveryResponse, EndpointResponse, PageResponse, ProjectResponse } from '../../types/api.types';
import type { SubscriptionResponse } from '../../api/subscriptions.api';

vi.mock('../../lib/toast', () => ({
  showApiError: vi.fn(),
  showError: vi.fn(),
  showSuccess: vi.fn(),
  showCriticalSuccess: vi.fn(),
}));
vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: {
    get: vi.fn(),
    list: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    rotateSecret: vi.fn(),
    test: vi.fn(),
    verify: vi.fn(),
    skipVerification: vi.fn(),
  },
}));
vi.mock('../../api/subscriptions.api', () => ({
  subscriptionsApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));
vi.mock('../../api/deliveries.api', () => ({
  deliveriesApi: { listByProject: vi.fn(), get: vi.fn(), getAttempts: vi.fn() },
}));
vi.mock('../../api/consumers.api', () => ({
  consumersApi: { listPaged: vi.fn() },
}));

import EndpointDetailPage from '../EndpointDetailPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { subscriptionsApi } from '../../api/subscriptions.api';
import { deliveriesApi } from '../../api/deliveries.api';
import { consumersApi } from '../../api/consumers.api';
import { showError } from '../../lib/toast';

const NOW = new Date().toISOString();

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: NOW,
  updatedAt: NOW,
};

const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1',
  projectId: TEST_PROJECT_ID,
  url: 'https://example.com/webhook',
  description: 'Production handler',
  enabled: true,
  rateLimitPerSecond: 25,
  verificationStatus: 'VERIFIED',
  signatureScheme: 'BOTH',
  createdAt: NOW,
  updatedAt: NOW,
};

function subscription(overrides: Partial<SubscriptionResponse>): SubscriptionResponse {
  return {
    id: 'sub-1',
    projectId: TEST_PROJECT_ID,
    endpointId: ENDPOINT.id,
    eventType: 'order.created',
    enabled: true,
    orderingEnabled: false,
    maxAttempts: 3,
    timeoutSeconds: 30,
    retryDelays: '60,300',
    payloadTemplate: null,
    customHeaders: null,
    transformationId: null,
    transformationName: null,
    createdAt: NOW,
    updatedAt: NOW,
    ...overrides,
  };
}

const DELIVERY = {
  id: 'delivery-1',
  eventId: 'event-1',
  eventType: 'order.created',
  endpointId: ENDPOINT.id,
  subscriptionId: 'sub-1',
  status: 'SUCCESS',
  attemptCount: 1,
  maxAttempts: 3,
  createdAt: NOW,
} as DeliveryResponse;

function page<T>(content: T[]): PageResponse<T> {
  return {
    content, totalElements: content.length, totalPages: 1, size: 10, number: 0, first: true, last: true,
  } as unknown as PageResponse<T>;
}

function Where() {
  const location = useLocation();
  return <p data-testid="where">{location.pathname + location.search}</p>;
}

function arrange(endpoint: EndpointResponse = ENDPOINT) {
  vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
  vi.mocked(endpointsApi.get).mockResolvedValue(endpoint);
  vi.mocked(endpointsApi.list).mockResolvedValue([endpoint]);
  vi.mocked(subscriptionsApi.list).mockResolvedValue([
    subscription({}),
    subscription({ id: 'sub-2', eventType: 'invoice.paid', endpointId: 'someone-else' }),
  ]);
  vi.mocked(deliveriesApi.listByProject).mockResolvedValue(page([DELIVERY]));
  vi.mocked(consumersApi.listPaged).mockResolvedValue(page([
    { id: 'consumer-1', projectId: TEST_PROJECT_ID, externalId: 'acme', name: 'Acme Corp', createdAt: NOW, updatedAt: NOW },
  ]) as never);
}

function renderDetail() {
  return renderPage(
    <Routes>
      <Route path="/admin/projects/:projectId/endpoints/:endpointId" element={<EndpointDetailPage />} />
      <Route path="*" element={<Where />} />
    </Routes>,
    { path: '*', initialEntry: `/admin/projects/${TEST_PROJECT_ID}/endpoints/${ENDPOINT.id}` },
  );
}

/**
 * An endpoint had no page of its own: its facts were spread over an expandable row on
 * Connections and a row of unlabelled icons on Endpoints, and nothing showed what it had been
 * sent lately. This page is where an endpoint is looked at and acted on.
 */
describe('EndpointDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('names the endpoint by its URL, with a way back to the list it was opened from', async () => {
    arrange();
    renderDetail();

    expect(await screen.findByRole('heading', { level: 1, name: ENDPOINT.url })).toBeInTheDocument();
    const crumbs = screen.getByRole('navigation', { name: /breadcrumb/i });
    expect(within(crumbs).getAllByRole('link', { name: 'Connections' })[0])
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/connections`);
  });

  it('lists the event types this endpoint is subscribed to, and no one else\'s', async () => {
    arrange();
    renderDetail();

    expect(await screen.findByText('order.created', { selector: 'code' })).toBeInTheDocument();
    expect(screen.queryByText('invoice.paid')).not.toBeInTheDocument();
  });

  it('says whose endpoint it is when it was registered for a consumer', async () => {
    arrange({ ...ENDPOINT, consumerId: 'consumer-1' });
    renderDetail();

    expect(await screen.findByRole('link', { name: 'Acme Corp' }))
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/consumers`);
  });

  it('shows what it was sent lately, and links to all of its deliveries', async () => {
    arrange();
    renderDetail();

    await waitFor(() => expect(deliveriesApi.listByProject).toHaveBeenCalledWith(
      TEST_PROJECT_ID, expect.objectContaining({ endpointId: ENDPOINT.id }),
    ));
    expect(await screen.findByText('Success')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /all deliveries to this endpoint/i }))
      .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/deliveries?endpointId=${ENDPOINT.id}`);
  });

  it('names every action in words rather than an icon alone', async () => {
    arrange();
    renderDetail();
    await screen.findByRole('heading', { level: 1, name: ENDPOINT.url });

    for (const name of ['Send a test request', 'Disable', 'Enable mTLS', 'Delete', 'Rotate secret']) {
      expect(screen.getByRole('button', { name })).toHaveTextContent(name);
    }
  });

  it('shows how to verify its signature, for the scheme it is sent', async () => {
    arrange({ ...ENDPOINT, signatureScheme: 'LEGACY' });
    renderDetail();

    expect(await screen.findByText('Verify the signature on your receiver')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'X-Signature only' })).toHaveAttribute('aria-checked', 'true');
  });

  it('changes the signature scheme without clearing the rate limit the update also carries', async () => {
    arrange();
    vi.mocked(endpointsApi.update).mockResolvedValue({ ...ENDPOINT, signatureScheme: 'STANDARD' });
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByRole('radio', { name: 'Standard Webhooks only' }));

    await waitFor(() =>
      expect(endpointsApi.update).toHaveBeenCalledWith(TEST_PROJECT_ID, ENDPOINT.id, {
        url: ENDPOINT.url,
        description: ENDPOINT.description,
        enabled: true,
        rateLimitPerSecond: 25,
        signatureScheme: 'STANDARD',
      }),
    );
  });

  it('shows the whsec_ form beside the raw secret after a rotation, for a scheme that uses it', async () => {
    arrange();
    vi.mocked(endpointsApi.rotateSecret).mockResolvedValue({
      ...ENDPOINT, secret: 'a'.repeat(64), standardWebhooksSecret: 'whsec_c2VjcmV0LWJ5dGVz',
    });
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByRole('button', { name: 'Rotate secret' }));
    const confirm = await screen.findByRole('dialog');
    await user.click(within(confirm).getByRole('button', { name: /rotate secret/i }));

    expect(await screen.findAllByTestId('signing-secret')).toHaveLength(2);
  });

  it('does not offer the whsec_ form to a LEGACY endpoint', async () => {
    arrange({ ...ENDPOINT, signatureScheme: 'LEGACY' });
    vi.mocked(endpointsApi.rotateSecret).mockResolvedValue({
      ...ENDPOINT, signatureScheme: 'LEGACY', secret: 'a'.repeat(64), standardWebhooksSecret: 'whsec_c2VjcmV0LWJ5dGVz',
    });
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByRole('button', { name: 'Rotate secret' }));
    const confirm = await screen.findByRole('dialog');
    await user.click(within(confirm).getByRole('button', { name: /rotate secret/i }));

    expect(await screen.findAllByTestId('signing-secret')).toHaveLength(1);
  });

  it('explains an offline tunnel when verification fails for that reason', async () => {
    arrange({ ...ENDPOINT, url: 'https://railhook.io/tunnel/tun-abc', verificationStatus: 'PENDING' });
    vi.mocked(endpointsApi.verify).mockResolvedValue({
      success: false, message: 'The tunnel is not connected.', status: 'FAILED', reason: 'TUNNEL_OFFLINE',
    });
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByRole('button', { name: 'Verify' }));

    await waitFor(() => expect(showError).toHaveBeenCalledTimes(1));
    expect(vi.mocked(showError).mock.calls[0][0]).toMatch(/railhook tunnel/);
  });

  it('goes back to Connections once the endpoint is deleted', async () => {
    arrange();
    vi.mocked(endpointsApi.delete).mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    const confirm = await screen.findByRole('dialog');
    await user.click(within(confirm).getByRole('button', { name: /delete/i }));

    await waitFor(() => expect(endpointsApi.delete).toHaveBeenCalledWith(TEST_PROJECT_ID, ENDPOINT.id));
    expect(await screen.findByTestId('where')).toHaveTextContent(`/admin/projects/${TEST_PROJECT_ID}/connections`);
  });

  it('has no detectable axe violations', async () => {
    arrange();
    const { container } = renderDetail();
    await screen.findByRole('heading', { level: 1, name: ENDPOINT.url });
    await screen.findByText('Success');
    expect(await axe(container)).toHaveNoViolations();
  });
});
