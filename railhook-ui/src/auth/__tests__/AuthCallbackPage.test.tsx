import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import AuthCallbackPage from '../AuthCallbackPage';
import { AuthContext, type AuthState } from '../auth.store';
import { authApi } from '../../api/auth.api';
import { http } from '../../api/http';
import type { CurrentUserResponse } from '../../types/api.types';

const USER = {
  user: { id: 'u1', email: 'grace@hopper.dev', fullName: 'Grace Hopper', status: 'ACTIVE' },
  organization: { id: 'o1', name: 'Hopper', createdAt: new Date().toISOString() },
  role: 'OWNER',
  emailDeliveryEnabled: true,
  hasPassword: false,
  platformAdmin: false,
} as unknown as CurrentUserResponse;

describe('AuthCallbackPage', () => {
  let login: Mock<AuthState['login']>;

  function renderAt(entry: string) {
    login = vi.fn<AuthState['login']>();
    return render(
      <AuthContext.Provider
        value={{ user: null, token: null, login, logout: () => {}, updateUser: () => {}, isAuthenticated: false }}
      >
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route path="/auth/callback" element={<AuthCallbackPage />} />
            <Route path="/admin/projects" element={<p>the projects screen</p>} />
            <Route path="/admin/dashboard" element={<p>the dashboard</p>} />
            <Route path="/login" element={<p>the login page</p>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    );
  }

  beforeEach(() => {
    vi.restoreAllMocks();
    http.setToken(null);
  });

  it('trades the code for a session and goes where the person was going', async () => {
    const exchange = vi.spyOn(authApi, 'exchangeSignInCode').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);

    renderAt('/auth/callback?code=one-time&returnTo=%2Fadmin%2Fprojects');

    expect(await screen.findByText('the projects screen')).toBeInTheDocument();
    expect(exchange).toHaveBeenCalledTimes(1);
    expect(exchange).toHaveBeenCalledWith('one-time');
    await waitFor(() => expect(login).toHaveBeenCalledWith('the-token', USER));
  });

  it('never follows a return address off this site', async () => {
    vi.spyOn(authApi, 'exchangeSignInCode').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);

    renderAt('/auth/callback?code=one-time&returnTo=%2F%2Fevil.example%2Fadmin');

    expect(await screen.findByText('the dashboard')).toBeInTheDocument();
  });

  it('offers the way back to sign-in when the code has been used or has expired', async () => {
    vi.spyOn(authApi, 'exchangeSignInCode').mockRejectedValue({ response: { status: 401 } });

    renderAt('/auth/callback?code=spent');

    const back = await screen.findByRole('link', { name: /sign in/i });
    expect(back).toHaveAttribute('href', '/login');
    expect(login).not.toHaveBeenCalled();
  });

  it('does not ask the API anything without a code', async () => {
    const exchange = vi.spyOn(authApi, 'exchangeSignInCode');

    renderAt('/auth/callback');

    expect(await screen.findByRole('link', { name: /sign in/i })).toHaveAttribute('href', '/login');
    expect(exchange).not.toHaveBeenCalled();
  });
});
