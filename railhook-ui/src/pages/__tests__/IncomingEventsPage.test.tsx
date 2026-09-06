import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, IncomingEventResponse, IncomingSourceResponse, PageResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/incomingSources.api', () => ({
  incomingSourcesApi: { list: vi.fn(), get: vi.fn() },
}));
vi.mock('../../api/incomingEvents.api', () => ({
  incomingEventsApi: {
    list: vi.fn(),
    get: vi.fn(),
    getAttempts: vi.fn(),
    replay: vi.fn(),
    bulkReplay: vi.fn(),
  },
}));

import IncomingEventsPage from '../IncomingEventsPage';
import { projectsApi } from '../../api/projects.api';
import { incomingSourcesApi } from '../../api/incomingSources.api';
import { incomingEventsApi } from '../../api/incomingEvents.api';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const SOURCE: IncomingSourceResponse = {
  id: 'source-1',
  projectId: TEST_PROJECT_ID,
  name: 'GitHub',
  slug: 'github',
  providerType: 'GITHUB',
  status: 'ACTIVE',
  ingressPathToken: 'tok',
  ingressUrl: 'https://ingress.example.com/tok',
  verificationMode: 'HMAC_GENERIC',
  hmacSecretConfigured: true,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const INCOMING_EVENT: IncomingEventResponse = {
  id: 'incoming-1',
  incomingSourceId: SOURCE.id,
  sourceName: SOURCE.name,
  requestId: 'req-12345678',
  method: 'POST',
  path: '/tok',
  verified: true,
  receivedAt: new Date().toISOString(),
};

function sourcesPage(items: IncomingSourceResponse[]): PageResponse<IncomingSourceResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 100, number: 0 } as any;
}
function emptyEventsPage(): PageResponse<IncomingEventResponse> {
  return { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true } as any;
}
function populatedEventsPage(items: IncomingEventResponse[]): PageResponse<IncomingEventResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0, first: true, last: true } as any;
}

function renderIncomingEvents() {
  return renderPage(<IncomingEventsPage />, {
    path: '/projects/:projectId/incoming-events',
    initialEntry: `/projects/${TEST_PROJECT_ID}/incoming-events`,
  });
}

describe('IncomingEventsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(incomingSourcesApi.list).mockResolvedValue(sourcesPage([SOURCE]));
  });

  it('renders a loading skeleton before data arrives', () => {
    vi.mocked(projectsApi.get).mockReturnValue(new Promise(() => {}));
    vi.mocked(incomingEventsApi.list).mockReturnValue(new Promise(() => {}));
    const { container } = renderIncomingEvents();
    expect(container.querySelector('.animate-pulse')).toBeTruthy();
  });

  it('renders the empty state when there are genuinely no incoming events', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(incomingEventsApi.list).mockResolvedValue(emptyEventsPage());
    renderIncomingEvents();
    expect(await screen.findByText(/no incoming events/i)).toBeInTheDocument();
  });

  it('renders populated rows when incoming events exist', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(incomingEventsApi.list).mockResolvedValue(populatedEventsPage([INCOMING_EVENT]));
    renderIncomingEvents();
    expect(await screen.findByText(/req-12345678/)).toBeInTheDocument();
  });

  it('has no detectable axe accessibility violations when populated', async () => {
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(incomingEventsApi.list).mockResolvedValue(populatedEventsPage([INCOMING_EVENT]));
    const { container } = renderIncomingEvents();
    await screen.findByText(/req-12345678/);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('renders an explicit error state — not the empty state — when the API is down', async () => {
    vi.mocked(incomingSourcesApi.list).mockRejectedValue({ request: {}, message: 'Network Error' });
    vi.mocked(projectsApi.get).mockRejectedValue({ request: {}, message: 'Network Error' });
    vi.mocked(incomingEventsApi.list).mockRejectedValue({ request: {}, message: 'Network Error' });
    renderIncomingEvents();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText(/no incoming events/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('shows what was actually forwarded, not only what came back', async () => {
    /* The Forward's request headers and body were added to incoming_forward_attempts and
       exposed on the response, and nothing rendered them — so debugging a failed Forward
       stayed structurally weaker than debugging a failed Delivery, which has shown both
       for as long as it has existed. "What did we send them" is the first question a
       failing integration asks. */
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(incomingEventsApi.list).mockResolvedValue(populatedEventsPage([INCOMING_EVENT]));
    vi.mocked(incomingEventsApi.getAttempts).mockResolvedValue({
      content: [{
        id: 'attempt-1',
        incomingEventId: INCOMING_EVENT.id,
        destinationId: 'dest-1',
        destinationUrl: 'https://internal.example.com/hook',
        attemptNumber: 1,
        status: 'FAILED',
        requestHeadersJson: '{"Authorization":"***","Content-Type":"application/json"}',
        requestBodySnippet: '{"order":"ord_42"}',
        responseCode: 500,
        createdAt: new Date().toISOString(),
      }],
      totalElements: 1, totalPages: 1, size: 20, number: 0, first: true, last: true,
    } as any);

    renderIncomingEvents();
    const row = await screen.findByText(INCOMING_EVENT.requestId!);
    await userEvent.click(row);

    expect(await screen.findByText('Forwarded request headers')).toBeInTheDocument();
    expect(screen.getByText('Forwarded request body')).toBeInTheDocument();
    expect(screen.getByText(/ord_42/)).toBeInTheDocument();
  });
});
