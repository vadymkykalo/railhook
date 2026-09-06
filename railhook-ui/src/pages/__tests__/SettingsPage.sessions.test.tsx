import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type { SessionResponse } from '../../api/auth.api';

vi.mock('../../api/auth.api', () => ({
  authApi: {
    listSessions: vi.fn(),
    revokeSession: vi.fn(),
    revokeAllSessions: vi.fn(),
    changePassword: vi.fn(),
    updateProfile: vi.fn(),
    switchOrganization: vi.fn(),
    getCurrentUser: vi.fn(),
  },
}));

import SettingsPage from '../SettingsPage';
import { authApi } from '../../api/auth.api';

function session(overrides: Partial<SessionResponse> = {}): SessionResponse {
  return {
    id: 'session-1',
    client: 'WEB',
    userAgent: 'Mozilla/5.0 (Macintosh)',
    ipAddress: '198.51.100.4',
    createdAt: new Date(Date.now() - 3_600_000).toISOString(),
    lastSeenAt: new Date(Date.now() - 60_000).toISOString(),
    expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
    current: false,
    ...overrides,
  };
}

/**
 * The account screen's answer to "what is signed in to this, and how do I stop it?".
 *
 * The CLI row is the one that earns the feature. A device-code grant is issued to a developer
 * machine and outlives it far more often than a browser tab does, and before sessions were
 * recorded there was no surface anywhere that admitted one existed.
 */
describe('SettingsPage — active sessions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  function renderSettings(auth?: Parameters<typeof renderPage>[1]['auth']) {
    return renderPage(<SettingsPage />, { path: '/settings', initialEntry: '/settings', auth });
  }

  it('lists a CLI grant next to a browser session, naming each', async () => {
    vi.mocked(authApi.listSessions).mockResolvedValue([
      session({ id: 'web-1', current: true }),
      session({ id: 'cli-1', client: 'CLI', userAgent: 'railhook-cli/2.9.1' }),
    ]);

    renderSettings();

    expect(await screen.findByText('Command line')).toBeInTheDocument();
    expect(screen.getByText('Browser')).toBeInTheDocument();
    expect(screen.getByText('This device')).toBeInTheDocument();
  });

  it('never renders token material for a session', async () => {
    vi.mocked(authApi.listSessions).mockResolvedValue([session({ id: 'web-1' })]);

    const { container } = renderSettings();
    await screen.findByText('Browser');

    /* The list is readable by anything holding an access token, so it must never be a place a
       stolen short-lived credential can be traded up for a long-lived one. */
    expect(container.textContent).not.toMatch(/refresh/i);
  });

  it('signs one session out and refreshes the list', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.listSessions).mockResolvedValue([
      session({ id: 'web-1', current: true }),
      session({ id: 'cli-1', client: 'CLI', userAgent: 'railhook-cli/2.9.1' }),
    ]);
    vi.mocked(authApi.revokeSession).mockResolvedValue(undefined);

    renderSettings();
    const cliRow = (await screen.findByText('Command line')).closest('li') as HTMLElement;

    await user.click(within(cliRow).getByRole('button', { name: /Sign out/i }));
    await user.click(await screen.findByRole('button', { name: /^Sign out$/i, hidden: false }));

    await waitFor(() => expect(authApi.revokeSession).toHaveBeenCalledWith('cli-1'));
    await waitFor(() => expect(authApi.listSessions).toHaveBeenCalledTimes(2));
  });

  it('warns before signing out the device you are using', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.listSessions).mockResolvedValue([session({ id: 'web-1', current: true })]);

    renderSettings();
    const currentRow = (await screen.findByText('This device')).closest('li') as HTMLElement;

    await user.click(within(currentRow).getByRole('button', { name: /Sign out/i }));

    /* Revoking your own session is legitimate, but it takes effect on the very next request --
       so it has to say so rather than leave the tab discovering it as a string of 401s. */
    expect(await screen.findByText(/This is the device you are using/i)).toBeInTheDocument();
  });

  it('offers sign-out-everywhere only when there is more than one session', async () => {
    vi.mocked(authApi.listSessions).mockResolvedValue([session({ id: 'web-1', current: true })]);

    renderSettings();
    await screen.findByText('Browser');

    expect(screen.queryByRole('button', { name: /Sign out everywhere/i })).not.toBeInTheDocument();
  });
});
