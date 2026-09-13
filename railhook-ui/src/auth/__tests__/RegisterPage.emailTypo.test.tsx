import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import '../../i18n';

import RegisterPage from '../RegisterPage';
import { AuthContext, type AuthState } from '../auth.store';
import { authApi } from '../../api/auth.api';

/**
 * The account this exists for was registered as `wheelet1228@gmail.con`, and never got a single
 * one of its verification mails.
 */
describe('RegisterPage — a mistyped address', () => {
  function renderRegister() {
    const authState: AuthState = {
      user: null, token: null, login: vi.fn(), logout: () => {}, updateUser: () => {}, isAuthenticated: false,
    };
    return render(
      <AuthContext.Provider value={authState}>
        <MemoryRouter initialEntries={['/register']}>
          <RegisterPage />
        </MemoryRouter>
      </AuthContext.Provider>,
    );
  }

  it('refuses .con, offers gmail.com, and takes it in one click', async () => {
    const register = vi.spyOn(authApi, 'register');
    const user = userEvent.setup();
    renderRegister();

    await user.type(screen.getByLabelText(/name/i, { selector: '#fullName' }), 'Wheelet');
    await user.type(screen.getByLabelText(/organization|company|workspace/i), 'Wheelet Org');
    await user.type(screen.getByLabelText(/^password/i), 'A str0ng! passphrase');
    await user.type(screen.getByLabelText(/email/i), 'wheelet1228@gmail.con');

    const submit = screen.getByRole('button', { name: /create|register|sign up/i });
    expect(submit).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'wheelet1228@gmail.com' }));

    expect(screen.getByLabelText(/email/i)).toHaveValue('wheelet1228@gmail.com');
    expect(screen.queryByText(/did you mean/i)).not.toBeInTheDocument();
    expect(submit).toBeEnabled();
    expect(register).not.toHaveBeenCalled();
  });
});
