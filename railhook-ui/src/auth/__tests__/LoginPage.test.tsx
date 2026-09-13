import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import LoginPage from '../LoginPage';
import { AuthContext, type AuthState } from '../auth.store';
import { authApi } from '../../api/auth.api';
import { http } from '../../api/http';
import type { CurrentUserResponse } from '../../types/api.types';

const USER = {
  user: { id: 'u1', email: 'someone@example.com', fullName: 'Someone', status: 'ACTIVE' },
  organization: { id: 'o1', name: 'Org', createdAt: new Date().toISOString() },
  role: 'OWNER',
} as unknown as CurrentUserResponse;

/**
 * The one screen every user meets, and the one that had no test at all. What matters here is
 * the order: the token has to reach the http client before getCurrentUser is called, or that
 * call goes out unauthenticated and a correct password looks like a failed sign-in.
 */
describe('LoginPage', () => {
  let login: Mock<AuthState['login']>;

  function renderLogin(state: Partial<AuthState> = {}) {
    login = vi.fn<AuthState['login']>();
    const authState: AuthState = {
      user: null,
      token: null,
      login,
      logout: () => {},
      updateUser: () => {},
      isAuthenticated: false,
      ...state,
    };
    return render(
      <AuthContext.Provider value={authState}>
        <MemoryRouter initialEntries={['/login']}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/admin/projects" element={<p>the projects screen</p>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    );
  }

  async function signIn(password = 'correct horse') {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), 'someone@example.com');
    await user.type(screen.getByLabelText(/password/i), password);
    await user.click(screen.getByRole('button', { name: /sign in|log in/i }));
  }

  beforeEach(() => {
    vi.restoreAllMocks();
    http.setToken(null);
  });

  it('signs the user in and takes them to the dashboard', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);

    renderLogin();
    await signIn();

    await waitFor(() => expect(login).toHaveBeenCalledWith('the-token', USER));
    expect(await screen.findByText('the projects screen')).toBeInTheDocument();
  });

  it('has the token in place before it asks who the user is', async () => {
    // getCurrentUser goes out on the shared http client. If the token is set after this call
    // rather than before it, the request is anonymous, the 401 path runs, and a correct
    // password presents as a failed sign-in.
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'the-token' } as never);
    // Captured at call time and asserted afterwards. An expect() inside the mock would reject
    // the promise instead of failing the test, and LoginPage would swallow it as a sign-in
    // error — the assertion would never be seen.
    let tokenWhenAsked: string | null | undefined;
    const whoAmI = vi.spyOn(authApi, 'getCurrentUser').mockImplementation(async () => {
      tokenWhenAsked = http.getToken();
      return USER;
    });

    renderLogin();
    await signIn();

    await waitFor(() => expect(whoAmI).toHaveBeenCalled());
    expect(tokenWhenAsked).toBe('the-token');
  });

  it('shows what the server said when the password is wrong, and stays put', async () => {
    vi.spyOn(authApi, 'login').mockRejectedValue({
      response: { data: { message: 'Invalid email or password' } },
    });

    renderLogin();
    await signIn('wrong');

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
    expect(login).not.toHaveBeenCalled();
    expect(screen.queryByText('the projects screen')).not.toBeInTheDocument();
  });

  it('still says something when the server said nothing useful', async () => {
    vi.spyOn(authApi, 'login').mockRejectedValue(new Error('Network Error'));

    renderLogin();
    await signIn();

    // A failure with no response body must not render an empty alert.
    const alert = await screen.findByRole('alert');
    expect(alert.textContent?.trim()).not.toBe('');
  });

  it('lets the user try again after a failure rather than staying disabled', async () => {
    vi.spyOn(authApi, 'login').mockRejectedValue(new Error('Network Error'));

    renderLogin();
    await signIn();

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: /sign in|log in/i })).toBeEnabled();
  });

  it('returns the user to the page that sent them to sign in', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);
    login = vi.fn<AuthState['login']>();

    render(
      <AuthContext.Provider
        value={{
          user: null, token: null, login, logout: () => {}, updateUser: () => {},
          isAuthenticated: false,
        }}
      >
        <MemoryRouter initialEntries={[{ pathname: '/login', state: { from: '/admin/endpoints' } }]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/admin/endpoints" element={<p>the endpoints screen</p>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    );
    await signIn();

    expect(await screen.findByText('the endpoints screen')).toBeInTheDocument();
  });
});
