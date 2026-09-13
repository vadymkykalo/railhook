import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import '../../i18n';

vi.mock('../../api/auth.api', () => ({
  authApi: { confirmEmailChange: vi.fn(), cancelEmailChangeByToken: vi.fn() },
}));

import ConfirmEmailChangePage from '../ConfirmEmailChangePage';
import CancelEmailChangePage from '../CancelEmailChangePage';
import { AuthContext, type AuthState } from '../auth.store';
import { authApi } from '../../api/auth.api';

function renderAt(path: string, logout = vi.fn()) {
  const auth: AuthState = {
    user: null, token: 'token', login: vi.fn(), logout, updateUser: vi.fn(), isAuthenticated: true,
  };
  render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/confirm-email-change" element={<ConfirmEmailChangePage />} />
          <Route path="/cancel-email-change" element={<CancelEmailChangePage />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
  return logout;
}

/** The two links the email change mails carry. Each ends every session, so this tab's too. */
describe('the email change links', () => {
  beforeEach(() => vi.clearAllMocks());

  it('confirming says the address changed and signs this tab out', async () => {
    vi.mocked(authApi.confirmEmailChange).mockResolvedValue(undefined);
    const logout = renderAt('/confirm-email-change?token=tok-confirm');

    expect(await screen.findByRole('heading', { name: /email changed/i })).toBeInTheDocument();
    expect(authApi.confirmEmailChange).toHaveBeenCalledWith('tok-confirm');
    expect(logout).toHaveBeenCalled();
  });

  it('a used or expired link says so', async () => {
    vi.mocked(authApi.confirmEmailChange).mockRejectedValue({
      response: { data: { message: 'This confirmation link has expired. Ask for the change again.' } },
    });
    renderAt('/confirm-email-change?token=old');

    expect(await screen.findByRole('alert')).toHaveTextContent(/has expired/i);
  });

  it('"this wasn\'t me" cancels, signs out, and points at a password reset', async () => {
    vi.mocked(authApi.cancelEmailChangeByToken).mockResolvedValue(undefined);
    const logout = renderAt('/cancel-email-change?token=tok-cancel');

    expect(await screen.findByRole('heading', { name: /change cancelled/i })).toBeInTheDocument();
    expect(authApi.cancelEmailChangeByToken).toHaveBeenCalledWith('tok-cancel');
    await waitFor(() => expect(logout).toHaveBeenCalled());
    expect(screen.getByRole('link', { name: /reset password/i })).toHaveAttribute('href', '/forgot-password');
  });
});
