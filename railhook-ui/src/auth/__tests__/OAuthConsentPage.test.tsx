import { describe, it, expect, vi, beforeEach } from 'vitest';
import { Suspense } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import '../../i18n';
import { AuthContext, type AuthState } from '../auth.store';
import { createTestQueryClient, renderPage } from '../../test/renderPage';
import type { McpConsentRequestResponse, ProjectResponse } from '../../types/api.types';

vi.mock('../../api/mcpApps.api', () => ({
  mcpAppsApi: { getRequest: vi.fn(), approve: vi.fn(), deny: vi.fn(), listGrants: vi.fn(), revokeGrant: vi.fn() },
}));
vi.mock('../../api/projects.api', () => ({
  projectsApi: { list: vi.fn(), get: vi.fn() },
}));
vi.mock('../../api/organizations.api', () => ({
  organizationsApi: { list: vi.fn() },
}));
vi.mock('../../lib/leavePage', () => ({ leaveTo: vi.fn() }));

import OAuthConsentPage from '../OAuthConsentPage';
import { mcpAppsApi } from '../../api/mcpApps.api';
import { projectsApi } from '../../api/projects.api';
import { organizationsApi } from '../../api/organizations.api';
import { leaveTo } from '../../lib/leavePage';

function project(id: string, name: string): ProjectResponse {
  return {
    id,
    name,
    schemaValidationEnabled: false,
    schemaValidationPolicy: 'WARN',
    idempotencyPolicy: 'NONE',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

function request(overrides: Partial<McpConsentRequestResponse> = {}): McpConsentRequestResponse {
  return {
    requestId: 'req-1',
    clientName: 'Claude',
    clientUri: null,
    redirectHost: 'claude.ai',
    requestedScope: 'READ_WRITE',
    canGrantWrite: true,
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    ...overrides,
  };
}

function renderConsent(search = '?request=req-1') {
  return renderPage(<OAuthConsentPage />, { path: '/oauth/consent', initialEntry: `/oauth/consent${search}` });
}

describe('OAuthConsentPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.list).mockResolvedValue([project('p-1', 'Payments'), project('p-2', 'Billing')]);
    vi.mocked(organizationsApi.list).mockResolvedValue([]);
  });

  it('names the app and where it returns to, and approves the chosen project and access', async () => {
    const user = userEvent.setup();
    vi.mocked(mcpAppsApi.getRequest).mockResolvedValue(request());
    vi.mocked(mcpAppsApi.approve).mockResolvedValue({ redirectUrl: 'https://claude.ai/cb?code=abc&state=s' });
    renderConsent();

    expect(await screen.findByRole('heading', { name: 'Connect Claude to Railhook' })).toBeInTheDocument();
    expect(screen.getAllByText('claude.ai').length).toBeGreaterThan(0);
    expect(screen.getByRole('radio', { name: /Read & Write/ })).toHaveAttribute('aria-checked', 'true');

    await user.click(screen.getByRole('radio', { name: /Read Only/ }));
    await user.click(screen.getByRole('button', { name: 'Connect' }));

    await waitFor(() => expect(mcpAppsApi.approve).toHaveBeenCalledWith('req-1', { projectId: 'p-1', scope: 'READ_ONLY' }));
    expect(leaveTo).toHaveBeenCalledWith('https://claude.ai/cb?code=abc&state=s');
    expect(await screen.findByText(/Returning you to claude.ai/)).toBeInTheDocument();
  });

  it('does not offer write access to a role that cannot create an API key', async () => {
    vi.mocked(mcpAppsApi.getRequest).mockResolvedValue(request({ canGrantWrite: false }));
    renderConsent();

    const write = await screen.findByRole('radio', { name: /Read & Write/ });
    expect(write).toBeDisabled();
    expect(screen.getByRole('radio', { name: /Read Only/ })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText(/owner or developer/)).toBeInTheDocument();
  });

  it('declines and sends the app its access_denied', async () => {
    const user = userEvent.setup();
    vi.mocked(mcpAppsApi.getRequest).mockResolvedValue(request());
    vi.mocked(mcpAppsApi.deny).mockResolvedValue({ redirectUrl: 'https://claude.ai/cb?error=access_denied' });
    renderConsent();

    await user.click(await screen.findByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(mcpAppsApi.deny).toHaveBeenCalledWith('req-1'));
    expect(mcpAppsApi.approve).not.toHaveBeenCalled();
    expect(leaveTo).toHaveBeenCalledWith('https://claude.ai/cb?error=access_denied');
  });

  it('says so when the request expired or was already answered', async () => {
    vi.mocked(mcpAppsApi.getRequest).mockRejectedValue({ response: { status: 404, data: { message: 'gone' } } });
    renderConsent();

    expect(await screen.findByRole('heading', { name: 'This request has expired' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Connect' })).not.toBeInTheDocument();
  });

  it('shows the error the authorization endpoint sent instead of asking anything', async () => {
    renderConsent('?error=invalid_request&error_description=The%20app%20asked%20to%20be%20sent%20somewhere%20else.');

    expect(await screen.findByRole('heading', { name: "This app can't be connected" })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('The app asked to be sent somewhere else.');
    expect(mcpAppsApi.getRequest).not.toHaveBeenCalled();
  });

  it('sends a signed-out visitor through sign-in and back to this request', async () => {
    function LoginProbe() {
      const location = useLocation();
      return <p>login:{location.search}</p>;
    }
    const auth: AuthState = {
      user: null, token: null, login: () => {}, logout: () => {}, updateUser: () => {}, isAuthenticated: false,
    };
    render(
      <Suspense fallback={null}>
        <QueryClientProvider client={createTestQueryClient()}>
          <AuthContext.Provider value={auth}>
            <MemoryRouter initialEntries={['/oauth/consent?request=req-1']}>
              <Routes>
                <Route path="/oauth/consent" element={<OAuthConsentPage />} />
                <Route path="/login" element={<LoginProbe />} />
              </Routes>
            </MemoryRouter>
          </AuthContext.Provider>
        </QueryClientProvider>
      </Suspense>,
    );

    expect(await screen.findByText(`login:?redirect=${encodeURIComponent('/oauth/consent?request=req-1')}`))
      .toBeInTheDocument();
    expect(mcpAppsApi.getRequest).not.toHaveBeenCalled();
    expect(projectsApi.list).not.toHaveBeenCalled();
  });
});
