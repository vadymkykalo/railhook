import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import ResetPasswordPage from '../ResetPasswordPage';
import { authApi } from '../../api/auth.api';

/**
 * The screen someone reaches from an email when they are already locked out. Getting it wrong
 * strands them: there is no signed-in session to fall back on and no second route to the same
 * outcome, so every branch below is one where the user has nowhere else to go.
 */
describe('ResetPasswordPage', () => {
  function renderAt(search: string) {
    return render(
      <MemoryRouter initialEntries={[`/reset-password${search}`]}>
        <Routes>
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/login" element={<p>the sign-in screen</p>} />
        </Routes>
      </MemoryRouter>,
    );
  }

  async function fill(password: string, confirmation: string) {
    const user = userEvent.setup();
    const boxes = screen.getAllByLabelText(/password/i);
    await user.type(boxes[0], password);
    await user.type(boxes[1], confirmation);
    await user.click(screen.getByRole('button', { name: /reset|set|save|submit|change/i }));
  }

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('refuses to show the form at all without a token, and offers a way out', () => {
    renderAt('');

    expect(screen.queryByRole('button', { name: /reset|set|save|submit|change/i })).not.toBeInTheDocument();
    // Two ways onward — ask for a fresh link, or go back and sign in — because a dead link is
    // the one state the user cannot resolve on this screen.
    expect(screen.getAllByRole('link').length).toBeGreaterThan(0);
  });

  it('resets the password with the token from the link', async () => {
    const reset = vi.spyOn(authApi, 'resetPassword').mockResolvedValue(undefined);

    renderAt('?token=the-token');
    await fill('a good long password', 'a good long password');

    await waitFor(() => expect(reset).toHaveBeenCalledWith('the-token', 'a good long password'));
  });

  it('does not send anything when the two passwords differ', async () => {
    const reset = vi.spyOn(authApi, 'resetPassword').mockResolvedValue(undefined);

    renderAt('?token=the-token');
    await fill('a good long password', 'a different one');

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(reset).not.toHaveBeenCalled();
  });

  it('does not send a password the backend would reject for length', async () => {
    // Checked here as well as on the server so the user is told before the round trip, and
    // so a rejected reset does not look like a broken token.
    const reset = vi.spyOn(authApi, 'resetPassword').mockResolvedValue(undefined);

    renderAt('?token=the-token');
    await fill('short', 'short');

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(reset).not.toHaveBeenCalled();
  });

  it('says what the server said when the token has expired', async () => {
    vi.spyOn(authApi, 'resetPassword').mockRejectedValue({
      response: { data: { message: 'This reset link has expired' } },
    });

    renderAt('?token=stale');
    await fill('a good long password', 'a good long password');

    expect(await screen.findByRole('alert')).toHaveTextContent('This reset link has expired');
  });

  it('lets the user try again after a failure', async () => {
    vi.spyOn(authApi, 'resetPassword').mockRejectedValue(new Error('Network Error'));

    renderAt('?token=the-token');
    await fill('a good long password', 'a good long password');

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: /reset|set|save|submit|change/i })).toBeEnabled();
  });

  it('confirms success rather than leaving the form looking untouched', async () => {
    vi.spyOn(authApi, 'resetPassword').mockResolvedValue(undefined);

    renderAt('?token=the-token');
    await fill('a good long password', 'a good long password');

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /reset|set|save|submit|change/i })).not.toBeInTheDocument());
  });
});
