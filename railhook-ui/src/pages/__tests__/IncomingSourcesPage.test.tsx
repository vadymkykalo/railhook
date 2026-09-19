import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { Route, Routes, useLocation } from 'react-router-dom';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { IncomingSourceResponse, PageResponse, ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({ projectsApi: { get: vi.fn(), list: vi.fn() } }));
vi.mock('../../api/incomingSources.api', () => ({
  incomingSourcesApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
}));

import IncomingSourcesPage from '../IncomingSourcesPage';
import { projectsApi } from '../../api/projects.api';
import { incomingSourcesApi } from '../../api/incomingSources.api';

const now = new Date().toISOString();
const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID, name: 'Test Project', schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN', idempotencyPolicy: 'NONE', createdAt: now, updatedAt: now,
};
const SOURCE: IncomingSourceResponse = {
  id: 'source-1', projectId: TEST_PROJECT_ID, name: 'Stripe', slug: 'stripe', providerType: 'GENERIC',
  status: 'ACTIVE', ingressPathToken: 'tok', ingressUrl: 'https://in.example.com/tok',
  verificationMode: 'HMAC_GENERIC', hmacHeaderName: 'X-Sig', hmacSignaturePrefix: 'sha256=',
  hmacSecretConfigured: true, rateLimitPerSecond: 50, createdAt: now, updatedAt: now,
};

function page(items: IncomingSourceResponse[]): PageResponse<IncomingSourceResponse> {
  return { content: items, totalElements: items.length, totalPages: 1, size: 20, number: 0 } as PageResponse<IncomingSourceResponse>;
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

async function openEdit() {
  renderPage(<IncomingSourcesPage />, {
    path: '/projects/:projectId/incoming-sources',
    initialEntry: `/projects/${TEST_PROJECT_ID}/incoming-sources`,
  });
  fireEvent.click((await screen.findAllByRole('button', { name: /^edit$/i }))[0]);
  await screen.findByLabelText(/signature header/i);
}

const save = () => fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

describe('IncomingSourcesPage — the list', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
  });

  it('names the provider once, the way the provider spells it, and says whether the source is on', async () => {
    vi.mocked(incomingSourcesApi.list).mockResolvedValue(page([
      { ...SOURCE, name: 'GitHub (acme/shop)', slug: 'github', providerType: 'GITHUB' },
    ]));
    renderPage(<IncomingSourcesPage />, {
      path: '/projects/:projectId/incoming-sources',
      initialEntry: `/projects/${TEST_PROJECT_ID}/incoming-sources`,
    });

    const row = (await screen.findByText('GitHub (acme/shop)')).closest('tr')!;
    expect(row).toHaveTextContent('GitHub');
    expect(row).not.toHaveTextContent('GITHUB');
    expect(row).not.toHaveTextContent(/\bgithub\b/);
    expect(row).toHaveTextContent(en.common.enabled);
  });

  it('keeps a slug that says something the provider does not', async () => {
    vi.mocked(incomingSourcesApi.list).mockResolvedValue(page([
      { ...SOURCE, name: 'Payments', slug: 'payments-eu', providerType: 'STRIPE' },
    ]));
    renderPage(<IncomingSourcesPage />, {
      path: '/projects/:projectId/incoming-sources',
      initialEntry: `/projects/${TEST_PROJECT_ID}/incoming-sources`,
    });

    const row = (await screen.findByText('Payments')).closest('tr')!;
    expect(row).toHaveTextContent('payments-eu');
    expect(row).toHaveTextContent('Stripe');
  });

  it('opens the source from anywhere on its row, not only from its name', async () => {
    vi.mocked(incomingSourcesApi.list).mockResolvedValue(page([SOURCE]));
    renderPage(
      <Routes>
        <Route path="/projects/:projectId/incoming-sources" element={<IncomingSourcesPage />} />
        <Route path="*" element={<Where />} />
      </Routes>,
      { path: '*', initialEntry: `/projects/${TEST_PROJECT_ID}/incoming-sources` },
    );

    const row = (await screen.findByText(SOURCE.ingressUrl)).closest('tr')!;
    fireEvent.click(row.querySelector('td:last-child')!.previousElementSibling!);

    expect(await screen.findByTestId('where'))
      .toHaveTextContent(`/admin/projects/${TEST_PROJECT_ID}/incoming-sources/${SOURCE.id}`);
  });
});

describe('IncomingSourcesPage — clearing a field on edit', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(incomingSourcesApi.list).mockResolvedValue(page([SOURCE]));
    vi.mocked(incomingSourcesApi.update).mockResolvedValue(SOURCE);
  });

  it('sends 0 for a cleared rate limit, which the API stores as "no limit of its own"', async () => {
    await openEdit();
    fireEvent.change(screen.getByLabelText(/rate limit/i), { target: { value: '' } });
    save();

    await waitFor(() => expect(incomingSourcesApi.update).toHaveBeenCalledTimes(1));
    expect(vi.mocked(incomingSourcesApi.update).mock.calls[0][2].rateLimitPerSecond).toBe(0);
  });

  it('sends an empty string for a cleared signature prefix, which the API stores', async () => {
    await openEdit();
    fireEvent.change(screen.getByLabelText(/signature prefix/i), { target: { value: '' } });
    save();

    await waitFor(() => expect(incomingSourcesApi.update).toHaveBeenCalledTimes(1));
    expect(vi.mocked(incomingSourcesApi.update).mock.calls[0][2].hmacSignaturePrefix).toBe('');
  });

  it('does not claim to have cleared a signature header the API cannot clear', async () => {
    await openEdit();
    fireEvent.change(screen.getByLabelText(/signature header/i), { target: { value: '' } });
    save();

    expect(await screen.findByText(/cannot be removed/i)).toBeInTheDocument();
    expect(incomingSourcesApi.update).not.toHaveBeenCalled();
  });
});
