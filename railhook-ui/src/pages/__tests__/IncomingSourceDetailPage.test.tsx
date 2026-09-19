import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { IncomingSourceResponse } from '../../types/api.types';

vi.mock('../../api/incomingSources.api', () => ({
  incomingSourcesApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/incomingDestinations.api', () => ({
  incomingDestinationsApi: { list: vi.fn() },
}));
vi.mock('../../api/transformations.api', () => ({
  transformationsApi: { list: vi.fn() },
}));

import IncomingSourceDetailPage from '../IncomingSourceDetailPage';
import { incomingSourcesApi } from '../../api/incomingSources.api';
import { incomingDestinationsApi } from '../../api/incomingDestinations.api';
import { transformationsApi } from '../../api/transformations.api';

const BASE: IncomingSourceResponse = {
  id: 'source-1',
  projectId: TEST_PROJECT_ID,
  name: 'Payments',
  slug: 'payments',
  providerType: 'STRIPE',
  status: 'ACTIVE',
  ingressPathToken: 'tok',
  ingressUrl: 'https://ingress.example.com/tok',
  verificationMode: 'PROVIDER',
  hmacHeaderName: 'X-Signature',
  hmacSecretConfigured: true,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
} as IncomingSourceResponse;

function renderSource(overrides: Partial<IncomingSourceResponse>) {
  vi.mocked(incomingSourcesApi.get).mockResolvedValue({ ...BASE, ...overrides } as IncomingSourceResponse);
  return renderPage(<IncomingSourceDetailPage />, {
    path: '/admin/projects/:projectId/incoming-sources/:sourceId',
    initialEntry: `/admin/projects/${TEST_PROJECT_ID}/incoming-sources/source-1`,
  });
}

/**
 * A Stripe source showed "X-Signature" and a cURL with no signature at all, so the one example the
 * page gave could only ever be answered with 401.
 */
describe('IncomingSourceDetailPage — how a request is signed', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(incomingDestinationsApi.list).mockResolvedValue(
      { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 } as never,
    );
    vi.mocked(transformationsApi.list).mockResolvedValue([] as never);
  });

  it('names the provider\'s own header and says to send a test from the provider, not an unsigned cURL', async () => {
    renderSource({ providerType: 'STRIPE', verificationMode: 'PROVIDER', hmacHeaderName: 'X-Signature' });

    expect(await screen.findByText('Stripe-Signature')).toBeInTheDocument();
    expect(screen.queryByText('X-Signature')).not.toBeInTheDocument();
    expect(screen.getByText(/Stripe signs every webhook in the Stripe-Signature header/)).toBeInTheDocument();
    expect(screen.queryByText(/curl -X POST/)).not.toBeInTheDocument();
    expect(screen.getByText('Provider signature')).toBeInTheDocument();
    expect(screen.queryByText('PROVIDER')).not.toBeInTheDocument();
  });

  it('gives a generic HMAC source a cURL that carries its signature header', async () => {
    renderSource({
      providerType: 'GENERIC', verificationMode: 'HMAC_GENERIC', hmacHeaderName: 'X-Acme-Signature', hmacSignaturePrefix: 'sha256=',
    });

    // Highlighted, so the command is split across spans: read it off the code panel as a whole.
    const example = await screen.findByRole('tabpanel');
    expect(example).toHaveTextContent('curl -X POST');
    expect(example).toHaveTextContent('-H "X-Acme-Signature: sha256=<hmac-sha256-hex-of-body>"');
    expect(screen.getByText('HMAC (generic)')).toBeInTheDocument();
  });

  it('says in words how a destination is authenticated, not the enum', async () => {
    vi.mocked(incomingDestinationsApi.list).mockResolvedValue({
      content: [{
        id: 'dest-1', incomingSourceId: 'source-1', url: 'https://ci.example.com/hook', authType: 'NONE',
        authConfigured: false, enabled: true, maxAttempts: 5, timeoutSeconds: 30, retryDelays: '60,300',
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
      }],
      totalElements: 1, totalPages: 1, size: 20, number: 0,
    } as never);
    renderSource({});

    const row = (await screen.findByText('https://ci.example.com/hook')).closest('tr')!;
    expect(row).toHaveTextContent(en.incomingDestinations.authTypes.NONE);
    expect(row).not.toHaveTextContent('NONE');
    expect(row).toHaveTextContent(en.common.enabled);
  });

  it('keeps the plain cURL for a source that checks nothing', async () => {
    renderSource({ providerType: 'GENERIC', verificationMode: 'NONE', hmacHeaderName: undefined, hmacSecretConfigured: false });

    const example = await screen.findByRole('tabpanel');
    expect(example).toHaveTextContent('curl -X POST');
    expect(example).not.toHaveTextContent('Signature');
    expect(screen.getByText('None')).toBeInTheDocument();
  });
});
