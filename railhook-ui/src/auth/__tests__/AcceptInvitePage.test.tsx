import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import AcceptInvitePage from '../AcceptInvitePage';
import { AuthContext, type AuthState } from '../auth.store';
import { membersApi } from '../../api/members.api';

/**
 * Accepting an invite is what puts someone inside another organization, so the case that
 * matters most is the one where it must *not* happen: no token, no organization, or nobody
 * signed in to accept as. A screen that called the endpoint anyway would be asking the backend
 * to decide something the URL had already got wrong.
 */
describe('AcceptInvitePage', () => {
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
      <AuthContext.Provider value={authState}>
        <MemoryRouter initialEntries={[`/accept-invite${search}`]}>
          <Routes>
            <Route path="/accept-invite" element={<AcceptInvitePage />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    );
  }

  beforeEach(() => {
    vi.restoreAllMocks();
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
    // And is given the way to do it, with this page as the destination.
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
