import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type { OrganizationResponse } from '../../api/organizations.api';

vi.mock('../../api/organizations.api', () => ({
  organizationsApi: { list: vi.fn(), get: vi.fn(), update: vi.fn(), delete: vi.fn(), exportData: vi.fn() },
}));
vi.mock('../../api/auth.api', () => ({
  authApi: { switchOrganization: vi.fn(), getCurrentUser: vi.fn() },
}));
vi.mock('../../api/http', () => ({
  http: { setToken: vi.fn(), get: vi.fn(), post: vi.fn() },
}));

import OrganizationSwitcher from '../../components/OrganizationSwitcher';
import { organizationsApi } from '../../api/organizations.api';
import { authApi } from '../../api/auth.api';
import { http } from '../../api/http';

const HOME: OrganizationResponse = { id: 'org-1', name: 'Test Org', createdAt: new Date().toISOString() };
const CLIENT: OrganizationResponse = { id: 'org-2', name: 'Client Co', createdAt: new Date().toISOString() };

describe('OrganizationSwitcher', () => {
  beforeEach(() => vi.clearAllMocks());

  function render() {
    return renderPage(<OrganizationSwitcher />, { path: '/', initialEntry: '/' });
  }

  it('stays a plain organization name when there is only one to be in', async () => {
    vi.mocked(organizationsApi.list).mockResolvedValue([HOME]);

    render();

    await waitFor(() => expect(organizationsApi.list).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: /Current organization/i })).not.toBeInTheDocument();
    expect(screen.getByText('Test Org')).toBeInTheDocument();
  });

  it('lists both organizations and marks the one you are in', async () => {
    const user = userEvent.setup();
    vi.mocked(organizationsApi.list).mockResolvedValue([HOME, CLIENT]);

    render();

    await user.click(await screen.findByRole('button', { name: /Current organization: Test Org/i }));
    const options = await screen.findAllByRole('option');
    expect(options).toHaveLength(2);
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    expect(options[1]).toHaveAttribute('aria-selected', 'false');
  });

  it('switching re-reads the account and drops everything cached for the old organization', async () => {
    const user = userEvent.setup();
    vi.mocked(organizationsApi.list).mockResolvedValue([HOME, CLIENT]);
    vi.mocked(authApi.switchOrganization).mockResolvedValue({
      accessToken: 'token-for-client-co',
      emailVerified: true,
    } as never);
    vi.mocked(authApi.getCurrentUser).mockResolvedValue({
      user: { id: 'user-1', email: 'owner@example.com', fullName: null, status: 'ACTIVE' },
      organization: CLIENT,
      role: 'VIEWER',
    } as never);

    render();

    await user.click(await screen.findByRole('button', { name: /Current organization: Test Org/i }));
    await user.click(await screen.findByText('Client Co'));

    await waitFor(() => expect(authApi.switchOrganization).toHaveBeenCalledWith('org-2'));
    /* The token must be installed before /auth/me, or the answer describes the old organization. */
    expect(http.setToken).toHaveBeenCalledWith('token-for-client-co');
    await waitFor(() => expect(authApi.getCurrentUser).toHaveBeenCalled());
  });
});
