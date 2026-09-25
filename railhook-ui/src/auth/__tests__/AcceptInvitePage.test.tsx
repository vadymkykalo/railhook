import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import AcceptInvitePage from '../AcceptInvitePage';
import { AuthContext, type AuthState } from '../auth.store';
import { membersApi } from '../../api/members.api';
import { queryKeys } from '../../api/queries';
import { createTestQueryClient } from '../../test/renderPage';

describe('AcceptInvitePage', () => {
  let queryClient = createTestQueryClient();

  function renderAt(search: string, isAuthenticated: boolean) {
    const authState: AuthState = {
      user: null,
      token: isAuthenticated ? 'a-token' : null,
      login: () => {},
      logout: () => {},
      updateUser: () => {},
      isAuthenticated,
    };
    return render(
      <QueryClientProvider client={queryClient}>
        <AuthContext.Provider value={authState}>
          <MemoryRouter initialEntries={[`/accept-invite${search}`]}>
            <Routes>
              <Route path="/accept-invite" element={<AcceptInvitePage />} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      </QueryClientProvider>,
    );
  }

  beforeEach(() => {
    vi.restoreAllMocks();
    queryClient = createTestQueryClient();
  });

  it('refreshes the list of organizations the switcher offers once the invite is accepted', async () => {
    vi.spyOn(membersApi, 'acceptInvite').mockResolvedValue(undefined as never);
    // Kept past the test client's gcTime of 0: nothing on this page observes the list.
    queryClient = new QueryClient();
    queryClient.setQueryData(queryKeys.organizations.mine, [{ id: 'org-home', name: 'Home' }]);

    renderAt('?token=the-token&orgId=org-1', true);

    await waitFor(() => expect(queryClient.getQueryState(queryKeys.organizations.mine)?.isInvalidated).toBe(true));
  });

  it('accepts the invite named in the link', async () => {
    const accept = vi.spyOn(membersApi, 'acceptInvite').mockResolvedValue(undefined as never);

    renderAt('?token=the-token&orgId=org-1', true);

    await waitFor(() => expect(accept).toHaveBeenCalledWith('org-1', 'the-token'));
  });

  it('does not call the backend when the link carries no token', async () => {
    const accept = vi.spyOn(membersApi, 'acceptInvite').mockResolvedValue(undefined as never);

    renderAt('?orgId=org-1', true);

    await waitFor(() => expect(screen.getAllByRole('link').length).toBeGreaterThan(0));
    expect(accept).not.toHaveBeenCalled();
  });

  it('does not call the backend when the link names no organization', async () => {
    const accept = vi.spyOn(membersApi, 'acceptInvite').mockResolvedValue(undefined as never);

    renderAt('?token=the-token', true);

    await waitFor(() => expect(screen.getAllByRole('link').length).toBeGreaterThan(0));
    expect(accept).not.toHaveBeenCalled();
  });

  it('does not accept on behalf of nobody: a signed-out visitor is asked to sign in first', async () => {
    const accept = vi.spyOn(membersApi, 'acceptInvite').mockResolvedValue(undefined as never);

    renderAt('?token=the-token&orgId=org-1', false);

    await waitFor(() => expect(accept).not.toHaveBeenCalled());
    expect(screen.getAllByRole('link').length).toBeGreaterThan(0);
  });

  it('says what the server said when the invite is spent or expired', async () => {
    vi.spyOn(membersApi, 'acceptInvite').mockRejectedValue({
      response: { data: { message: 'This invitation has already been used' } },
    });

    renderAt('?token=used&orgId=org-1', true);

    expect(await screen.findByText(/already been used/i)).toBeInTheDocument();
  });

  it('does not leave the spinner up when the request fails without a message', async () => {
    vi.spyOn(membersApi, 'acceptInvite').mockRejectedValue(new Error('Network Error'));

    renderAt('?token=the-token&orgId=org-1', true);

    await waitFor(() => expect(screen.getAllByRole('link').length).toBeGreaterThan(0));
  });
});
