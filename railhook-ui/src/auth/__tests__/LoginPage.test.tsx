import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import LoginPage from '../LoginPage';
import { AuthContext, type AuthState } from '../auth.store';
import { authApi } from '../../api/auth.api';
import { http } from '../../api/http';
import { rememberSignedOutUser } from '../../lib/signInDestination';
import type { CurrentUserResponse } from '../../types/api.types';

const USER = {
  user: { id: 'u1', email: 'someone@example.com', fullName: 'Someone', status: 'ACTIVE' },
  organization: { id: 'o1', name: 'Org', createdAt: new Date().toISOString() },
  role: 'OWNER',
} as unknown as CurrentUserResponse;

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
    sessionStorage.clear();
  });

  function renderAtEntry(entry: string | { pathname: string; search?: string; state?: unknown }) {
    login = vi.fn<AuthState['login']>();
    return render(
      <AuthContext.Provider
        value={{
          user: null, token: null, login, logout: () => {}, updateUser: () => {},
          isAuthenticated: false,
        }}
      >
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<p>the register screen</p>} />
            <Route path="/admin/projects" element={<p>the projects screen</p>} />
            <Route path="/admin/projects/:projectId/endpoints" element={<p>their project's endpoints</p>} />
            <Route path="/accept-invite" element={<p>the invite screen</p>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    );
  }

  it('signs the user in and takes them to the dashboard', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);

    renderLogin();
    await signIn();

    await waitFor(() => expect(login).toHaveBeenCalledWith('the-token', USER));
    expect(await screen.findByText('the projects screen')).toBeInTheDocument();
  });

  it('has the token in place before it asks who the user is', async () => {
    // The token must be set before getCurrentUser, or a correct password looks like a failed sign-in.
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'the-token' } as never);
    // An expect() inside the mock would be swallowed by LoginPage as a sign-in error.
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

  it('says the page\'s address was refused, not that the person lacks permission, on a 403', async () => {
    // Spring's bare 403 for an unlisted origin read as "no permission".
    vi.spyOn(authApi, 'login').mockRejectedValue({
      response: { status: 403, data: 'Invalid CORS request' },
    });

    renderLogin();
    await signIn();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/CORS_ALLOWED_ORIGINS/);
    expect(alert).not.toHaveTextContent(/permission/i);
  });

  it('still says something when the server said nothing useful', async () => {
    vi.spyOn(authApi, 'login').mockRejectedValue(new Error('Network Error'));

    renderLogin();
    await signIn();

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

  it('continues to the invite a signed-out visitor was sent here from (?redirect=)', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);

    renderAtEntry(`/login?redirect=${encodeURIComponent('/accept-invite?token=t&orgId=o2')}`);
    await signIn();

    expect(await screen.findByText('the invite screen')).toBeInTheDocument();
  });

  it('keeps ?redirect= on the way to creating an account instead', () => {
    renderAtEntry(`/login?redirect=${encodeURIComponent('/accept-invite?token=t&orgId=o2')}`);

    expect(screen.getByRole('link', { name: /create/i }))
      .toHaveAttribute('href', `/register?redirect=${encodeURIComponent('/accept-invite?token=t&orgId=o2')}`);
  });

  it('does not follow ?redirect= off this site', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);

    renderAtEntry(`/login?redirect=${encodeURIComponent('//evil.example/phish')}`);
    await signIn();

    expect(await screen.findByText('the projects screen')).toBeInTheDocument();
  });

  it('does not send a different person to the page the previous one was signed out of', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);
    rememberSignedOutUser('someone-else');

    renderAtEntry({ pathname: '/login', state: { from: '/admin/projects/their-project/endpoints' } });
    await signIn();

    expect(await screen.findByText('the projects screen')).toBeInTheDocument();
  });

  it('does return the same person to where their session ended', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);
    rememberSignedOutUser('u1');

    renderAtEntry({ pathname: '/login', state: { from: '/admin/projects/their-project/endpoints' } });
    await signIn();

    expect(await screen.findByText("their project's endpoints")).toBeInTheDocument();
  });
});
