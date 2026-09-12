import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import RegisterPage from '../RegisterPage';
import { AuthContext, type AuthState } from '../auth.store';
import { authApi } from '../../api/auth.api';
import { http } from '../../api/http';
import type { CurrentUserResponse } from '../../types/api.types';

const USER = {
  user: { id: 'u1', email: 'new@example.com', fullName: 'New Person', status: 'PENDING' },
  organization: { id: 'o1', name: 'New Org', createdAt: new Date().toISOString() },
  role: 'OWNER',
} as unknown as CurrentUserResponse;

/**
 * The first thing anyone does with the product, and the only screen where a failure means the
 * person never becomes a user at all. The case worth guarding is the error path: the API
 * answers a rejected field with fieldErrors, and a version of this page that showed only the
 * generic summary told people "Invalid request parameters" and nothing they could act on.
 */
describe('RegisterPage', () => {
  let login: ReturnType<typeof vi.fn>;

  function renderRegister() {
    login = vi.fn();
    const authState: AuthState = {
      user: null,
      token: null,
      login,
      logout: () => {},
      updateUser: () => {},
      isAuthenticated: false,
    };
    return render(
      <AuthContext.Provider value={authState}>
        <MemoryRouter initialEntries={['/register']}>
          <Routes>
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/admin/dashboard" element={<p>the dashboard</p>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    );
  }

  async function fillAndSubmit(password = 'A str0ng! passphrase') {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/name/i, { selector: '#fullName' }), 'New Person');
    await user.type(screen.getByLabelText(/email/i), 'new@example.com');
    await user.type(screen.getByLabelText(/^password/i), password);
    const org = screen.queryByLabelText(/organization|company|workspace/i);
    if (org) {
      await user.type(org, 'New Org');
    }
    await user.click(screen.getByRole('button', { name: /create|register|sign up/i }));
  }

  beforeEach(() => {
    vi.restoreAllMocks();
    http.setToken(null);
  });

  it('registers, then signs the new user in', async () => {
    const register = vi.spyOn(authApi, 'register').mockResolvedValue({ accessToken: 'the-token' } as never);
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(USER);

    renderRegister();
    await fillAndSubmit();

    await waitFor(() => expect(register).toHaveBeenCalled());
    await waitFor(() => expect(login).toHaveBeenCalledWith('the-token', USER));
  });

  it('has the token in place before it asks who the new user is', async () => {
    vi.spyOn(authApi, 'register').mockResolvedValue({ accessToken: 'the-token' } as never);
    const whoAmI = vi.spyOn(authApi, 'getCurrentUser').mockImplementation(async () => {
      expect(http.getToken()).toBe('the-token');
      return USER;
    });

    renderRegister();
    await fillAndSubmit();

    await waitFor(() => expect(whoAmI).toHaveBeenCalled());
  });

  it('shows the rejected field, not the generic summary that says nothing', async () => {
    vi.spyOn(authApi, 'register').mockRejectedValue({
      response: {
        data: {
          message: 'Invalid request parameters',
          fieldErrors: { email: 'Email is already registered' },
        },
      },
    });

    renderRegister();
    await fillAndSubmit();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Email is already registered');
    expect(alert).not.toHaveTextContent('Invalid request parameters');
  });

  it('joins several rejected fields rather than picking one', async () => {
    vi.spyOn(authApi, 'register').mockRejectedValue({
      response: {
        data: {
          message: 'Invalid request parameters',
          fieldErrors: { email: 'Email is invalid', password: 'Password is too common' },
        },
      },
    });

    renderRegister();
    await fillAndSubmit();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Email is invalid');
    expect(alert).toHaveTextContent('Password is too common');
  });

  it('falls back to the summary when there are no field errors', async () => {
    vi.spyOn(authApi, 'register').mockRejectedValue({
      response: { data: { message: 'Registration is disabled on this instance' } },
    });

    renderRegister();
    await fillAndSubmit();

    expect(await screen.findByRole('alert')).toHaveTextContent('Registration is disabled on this instance');
  });

  it('does not sign anyone in when registration failed', async () => {
    vi.spyOn(authApi, 'register').mockRejectedValue(new Error('Network Error'));

    renderRegister();
    await fillAndSubmit();

    await screen.findByRole('alert');
    expect(login).not.toHaveBeenCalled();
    expect(http.getToken()).toBeNull();
  });

  it('lets the person try again after a failure', async () => {
    vi.spyOn(authApi, 'register').mockRejectedValue(new Error('Network Error'));

    renderRegister();
    await fillAndSubmit();

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: /create|register|sign up/i })).toBeEnabled();
  });
});
