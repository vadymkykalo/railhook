import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import { DISMISS_KEY, INTENT_KEY } from '../../lib/onboarding';
import type { OnboardingStatus } from '../../api/dashboard.api';
import type { IncomingSourceResponse } from '../../types/api.types';

vi.mock('../../api/dashboard.api', () => ({
  dashboardApi: { getOnboardingStatus: vi.fn() },
}));
vi.mock('../../api/incomingSources.api', () => ({
  incomingSourcesApi: { list: vi.fn() },
}));
vi.mock('../../api/subscriptions.api', () => ({
  subscriptionsApi: { list: vi.fn(), create: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn(), create: vi.fn(), test: vi.fn(), update: vi.fn() },
}));
vi.mock('../../api/events.api', () => ({
  eventsApi: { sendTest: vi.fn(), listEventTypes: vi.fn() },
}));
vi.mock('../../api/schemas.api', () => ({
  schemasApi: { listEventTypes: vi.fn() },
}));

import GettingStarted from '../GettingStarted';
import { dashboardApi } from '../../api/dashboard.api';
import { incomingSourcesApi } from '../../api/incomingSources.api';
import { subscriptionsApi } from '../../api/subscriptions.api';
import { schemasApi } from '../../api/schemas.api';

const NOTHING: OnboardingStatus = {
  hasEndpoints: false,
  hasSubscriptions: false,
  hasApiKeys: false,
  hasEvents: false,
  hasDeliveries: false,
  hasIncomingSources: false,
  hasIncomingDestinations: false,
};

const SOURCE: IncomingSourceResponse = {
  id: 'src-1',
  projectId: TEST_PROJECT_ID,
  name: 'Stripe',
  slug: 'stripe',
  providerType: 'STRIPE',
  status: 'ACTIVE',
  ingressPathToken: 'tok',
  ingressUrl: 'https://example.test/ingress/tok',
  verificationMode: 'NONE',
  hmacSecretConfigured: false,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const emptyPage = { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true };

function render(status: Partial<OnboardingStatus> = {}, sources: IncomingSourceResponse[] = []) {
  vi.mocked(dashboardApi.getOnboardingStatus).mockResolvedValue({ ...NOTHING, ...status });
  vi.mocked(incomingSourcesApi.list).mockResolvedValue({ ...emptyPage, content: sources });
  return renderPage(<GettingStarted projectId={TEST_PROJECT_ID} />, {
    path: '/admin/projects/:projectId/dashboard',
    initialEntry: `/admin/projects/${TEST_PROJECT_ID}/dashboard`,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  vi.mocked(subscriptionsApi.list).mockResolvedValue([]);
  vi.mocked(schemasApi.listEventTypes).mockResolvedValue([]);
});

describe('GettingStarted', () => {
  it('asks for the direction when the account is empty and nothing was answered', async () => {
    render();
    expect(await screen.findByText('What brings you to Railhook?')).toBeInTheDocument();
    expect(screen.queryByText('Create a connection')).not.toBeInTheDocument();
  });

  it('honours the answer it is given', async () => {
    // The bug this component replaces: the picker wrote the answer and the app
    // never read it.
    render();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('radio', { name: /I receive webhooks/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('Connect a source')).toBeInTheDocument();
    expect(screen.queryByText('Create a connection')).not.toBeInTheDocument();
  });

  it('does not ask again once the direction is stored', async () => {
    localStorage.setItem(INTENT_KEY, 'send');
    render();
    expect(await screen.findByText('Create a connection')).toBeInTheDocument();
    expect(screen.queryByText('What brings you to Railhook?')).not.toBeInTheDocument();
  });

  it('lets what the account has outrank what was answered', async () => {
    localStorage.setItem(INTENT_KEY, 'send');
    render({ hasIncomingSources: true }, [SOURCE]);
    expect(await screen.findByText('Connect a source')).toBeInTheDocument();
    expect(screen.queryByText('Create a connection')).not.toBeInTheDocument();
  });

  it('opens the connection wizard in place instead of navigating away', async () => {
    // "Go there" used to close the tour and drop you on a page with nothing
    // carried over. A step that builds something has to build it here.
    localStorage.setItem(INTENT_KEY, 'send');
    render();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /Create a connection/ }));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(window.location.pathname).not.toContain('/connections');
  });

  it('offers three incoming steps, and none the backend cannot report', async () => {
    localStorage.setItem(INTENT_KEY, 'receive');
    render();

    expect(await screen.findByText('Connect a source')).toBeInTheDocument();
    expect(screen.getByText('Verify what it sends')).toBeInTheDocument();
    expect(screen.getByText('Add a destination')).toBeInTheDocument();
    expect(screen.queryByText(/test webhook/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/event forwarding/i)).not.toBeInTheDocument();
  });

  it('leaves verification open on a source that does not verify', async () => {
    localStorage.setItem(INTENT_KEY, 'receive');
    render({ hasIncomingSources: true }, [SOURCE]);

    const verify = await screen.findByText('Verify what it sends');
    expect(verify.closest('li')).toHaveAttribute('data-done', 'false');
    expect(screen.getByText('Connect a source').closest('li')).toHaveAttribute('data-done', 'true');
  });

  it('reaches the finish line on the incoming track', async () => {
    localStorage.setItem(INTENT_KEY, 'receive');
    render(
      { hasIncomingSources: true, hasIncomingDestinations: true },
      [{ ...SOURCE, verificationMode: 'PROVIDER', hmacSecretConfigured: true }]
    );
    expect(await screen.findByText(/You're all set/)).toBeInTheDocument();
  });

  it('can be dismissed and brought back', async () => {
    localStorage.setItem(INTENT_KEY, 'send');
    render();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Dismiss' }));
    await waitFor(() => expect(screen.queryByText('Create a connection')).not.toBeInTheDocument());

    // Only this project is hidden — the flag is a list, not a boolean.
    expect(JSON.parse(localStorage.getItem(DISMISS_KEY)!)).toEqual([TEST_PROJECT_ID]);

    await user.click(screen.getByRole('button', { name: /Getting started/ }));
    expect(await screen.findByText('Create a connection')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem(DISMISS_KEY)!)).toEqual([]);
  });

  it('renders nothing at all without a project', () => {
    const { container } = renderPage(<GettingStarted projectId={undefined} />, {
      path: '/admin/dashboard',
      initialEntry: '/admin/dashboard',
    });
    expect(container).toBeEmptyDOMElement();
  });

  it('has no accessibility violations', async () => {
    localStorage.setItem(INTENT_KEY, 'send');
    const { container } = render();
    await screen.findByText('Create a connection');
    expect(await axe(container)).toHaveNoViolations();
  });
});
