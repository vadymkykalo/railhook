import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import '../../i18n';

import RegisterPage from '../RegisterPage';
import { AuthContext, type AuthState } from '../auth.store';
import { authApi } from '../../api/auth.api';

/** A real account registered as wheelet1228@gmail.con and never got a verification mail. */
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

    // Pasted, not typed: per-key re-renders outran the test timeout under a full run.
    const fill = async (field: HTMLElement, value: string) => {
      await user.click(field);
      await user.paste(value);
    };
    await fill(screen.getByLabelText(/name/i, { selector: '#fullName' }), 'Wheelet');
    await fill(screen.getByLabelText(/organization|company|workspace/i), 'Wheelet Org');
    await fill(screen.getByLabelText(/^password/i), 'A str0ng! passphrase');
    await fill(screen.getByLabelText(/email/i), 'wheelet1228@gmail.con');

    const submit = screen.getByRole('button', { name: /create|register|sign up/i });
    expect(submit).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'wheelet1228@gmail.com' }));

    expect(screen.getByLabelText(/email/i)).toHaveValue('wheelet1228@gmail.com');
    expect(screen.queryByText(/did you mean/i)).not.toBeInTheDocument();
    expect(submit).toBeEnabled();
    expect(register).not.toHaveBeenCalled();
  });
});
