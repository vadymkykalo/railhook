import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage } from '../../test/renderPage';

vi.mock('../../lib/toast', () => ({
  showApiError: vi.fn(),
  showSuccess: vi.fn(),
}));

vi.mock('../../api/auth.api', () => ({
  authApi: {
    listSessions: vi.fn(),
    revokeSession: vi.fn(),
    revokeAllSessions: vi.fn(),
    changePassword: vi.fn(),
    updateProfile: vi.fn(),
    switchOrganization: vi.fn(),
    getCurrentUser: vi.fn(),
    eraseOwnAccount: vi.fn(),
    getEmailChange: vi.fn().mockResolvedValue({ email: 'owner@example.com', applied: false }),
  },
}));

import SettingsPage from '../SettingsPage';
import { authApi } from '../../api/auth.api';
import { showApiError, showSuccess } from '../../lib/toast';

describe('SettingsPage — erasing your account', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(authApi.listSessions).mockResolvedValue([]);
  });

  function render() {
    return renderPage(<SettingsPage />, { path: '/settings', initialEntry: '/settings' });
  }

  it('does not erase anything on the first click', async () => {
    const user = userEvent.setup();
    render();

    await user.click((await screen.findAllByRole('button', { name: /erase my account/i }))[0]);

    expect(authApi.eraseOwnAccount).not.toHaveBeenCalled();
  });

  it('keeps the confirmation disabled until the account is typed back', async () => {
    const user = userEvent.setup();
    render();

    await user.click((await screen.findAllByRole('button', { name: /erase my account/i }))[0]);

    const dialog = await screen.findByRole('dialog');
    const confirm = await within(dialog).findByRole('button', { name: /erase my account/i });
    expect(confirm).toBeDisabled();
  });

  it('erases once the account is confirmed by name', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.eraseOwnAccount).mockResolvedValue(undefined);
    render();

    await user.click((await screen.findAllByRole('button', { name: /erase my account/i }))[0]);
    const dialog = await screen.findByRole('dialog');
    // renderPage signs in as owner@example.com.
    await user.type(within(dialog).getByRole('textbox'), 'owner@example.com');
    await user.click(within(dialog).getByRole('button', { name: /erase my account/i }));

    await waitFor(() => expect(authApi.eraseOwnAccount).toHaveBeenCalledTimes(1));
  });

  it('does not sign the person out when the erasure was refused', async () => {
    // A 409 erased nothing, so signing out would strand them outside a live account.
    const user = userEvent.setup();
    vi.mocked(authApi.eraseOwnAccount).mockRejectedValue({
      response: { data: { message: 'You are the last owner of an organization that still has other members.' } },
    });
    render();

    await user.click((await screen.findAllByRole('button', { name: /erase my account/i }))[0]);
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'owner@example.com');
    await user.click(within(dialog).getByRole('button', { name: /erase my account/i }));

    await waitFor(() => expect(showApiError).toHaveBeenCalled());
    expect(showSuccess).not.toHaveBeenCalled();
    expect(screen.getAllByRole('button', { name: /erase my account/i }).length).toBeGreaterThan(0);
  });

  it('spells out what will be lost before asking', async () => {
    const user = userEvent.setup();
    render();

    await user.click((await screen.findAllByRole('button', { name: /erase my account/i }))[0]);

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent(/only member of is deleted|alone in is deleted/i);
    expect(dialog).toHaveTextContent(/cannot be undone|can be undone/i);
  });
});
