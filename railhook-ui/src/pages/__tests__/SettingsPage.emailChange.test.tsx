import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type { CurrentUserResponse } from '../../types/api.types';

vi.mock('../../api/auth.api', () => ({
  authApi: {
    listSessions: vi.fn().mockResolvedValue([]),
    revokeSession: vi.fn(),
    revokeAllSessions: vi.fn(),
    changePassword: vi.fn(),
    updateProfile: vi.fn(),
    switchOrganization: vi.fn(),
    getCurrentUser: vi.fn(),
    providers: vi.fn().mockResolvedValue({ google: true }),
    getEmailChange: vi.fn(),
    requestEmailChange: vi.fn(),
    resendEmailChange: vi.fn(),
    cancelEmailChange: vi.fn(),
  },
}));

import SettingsPage from '../SettingsPage';
import { authApi } from '../../api/auth.api';

const VERIFIED = {
  user: { id: 'user-1', email: 'owner@example.com', fullName: 'Owner', status: 'ACTIVE' },
  organization: { id: 'org-1', name: 'Acme', createdAt: new Date().toISOString() },
  role: 'OWNER',
  emailDeliveryEnabled: true,
  hasPassword: true,
} as unknown as CurrentUserResponse;

const PENDING = {
  email: 'owner@example.com',
  pendingEmail: 'owner-new@example.com',
  pendingExpiresAt: new Date(Date.now() + 86_400_000).toISOString(),
  applied: false,
};

/**
 * A verified account's address changes only when the new one is proved, so Settings has to show
 * the in-between: which address is waiting, and the two things a person can do about it.
 */
describe('SettingsPage — changing the email address', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(authApi.getEmailChange).mockResolvedValue({ email: 'owner@example.com', applied: false });
  });

  function renderSettings(user: CurrentUserResponse = VERIFIED) {
    return renderPage(<SettingsPage />, { path: '/settings', initialEntry: '/settings', auth: { user } });
  }

  it('asks for the password, then waits for the new address with resend and cancel', async () => {
    vi.mocked(authApi.requestEmailChange).mockResolvedValue(PENDING);
    vi.mocked(authApi.resendEmailChange).mockResolvedValue(PENDING);
    vi.mocked(authApi.cancelEmailChange).mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderSettings();

    await user.click(await screen.findByRole('button', { name: /^change email$/i }));
    // Pasted, not typed: one keystroke at a time re-renders the whole Settings page per key and
    // outran the test timeout under a full run. What matters is what the form sends.
    await user.click(screen.getByLabelText(/new email address/i));
    await user.paste('owner-new@example.com');
    await user.click(screen.getByLabelText(/current password/i, { selector: '#email-change-password' }));
    await user.paste('Test1234!');
    await user.click(screen.getByRole('button', { name: /send confirmation link/i }));

    await waitFor(() => expect(authApi.requestEmailChange).toHaveBeenCalledWith({
      newEmail: 'owner-new@example.com', currentPassword: 'Test1234!',
    }));
    expect(await screen.findByText(/waiting for confirmation at/i)).toHaveTextContent('owner-new@example.com');

    await user.click(screen.getByRole('button', { name: /resend link/i }));
    await waitFor(() => expect(authApi.resendEmailChange).toHaveBeenCalled());

    await user.click(screen.getByRole('button', { name: /cancel change/i }));
    await waitFor(() => expect(authApi.cancelEmailChange).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText(/waiting for confirmation at/i)).not.toBeInTheDocument());
  });

  it('shows a change that was already waiting when the page opens', async () => {
    vi.mocked(authApi.getEmailChange).mockResolvedValue(PENDING);
    renderSettings();

    expect(await screen.findByText(/waiting for confirmation at/i)).toHaveTextContent('owner-new@example.com');
    expect(screen.getByText('owner@example.com')).toBeInTheDocument();
  });

  it('sends a Google-only account to sign in again instead of asking for a password it does not have', async () => {
    const user = userEvent.setup();
    renderSettings({ ...VERIFIED, hasPassword: false } as CurrentUserResponse);

    await user.click(await screen.findByRole('button', { name: /^change email$/i }));

    expect(screen.queryByLabelText(/current password/i, { selector: '#email-change-password' })).not.toBeInTheDocument();
    expect(screen.getByText(/signs in with google/i)).toBeInTheDocument();
  });
});
