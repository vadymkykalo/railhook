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

/**
 * The control that makes a second organization reachable at all.
 *
 * `GET /api/v1/orgs` has always returned every organization a user belongs to, and nothing in
 * the app ever called it: login minted a token for the oldest membership and refresh minted the
 * same one again, so accepting an invite to a second organization silently changed nothing you
 * could see. Two properties matter here — that the control appears only when there is a genuine
 * choice, and that taking it replaces the whole cached view rather than showing one
 * organization's rows under another's name.
 */
describe('OrganizationSwitcher', () => {
  beforeEach(() => vi.clearAllMocks());

  function render() {
    return renderPage(<OrganizationSwitcher />, { path: '/', initialEntry: '/' });
  }

  it('stays a plain organization name when there is only one to be in', async () => {
    vi.mocked(organizationsApi.list).mockResolvedValue([HOME]);

    render();

    await waitFor(() => expect(organizationsApi.list).toHaveBeenCalled());
    /* A switcher over a list of one is a control that answers a question nobody asked. */
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
    /* The token has to be installed before /auth/me is asked anything, or the answer describes
       the organization we are trying to leave. */
    expect(http.setToken).toHaveBeenCalledWith('token-for-client-co');
    await waitFor(() => expect(authApi.getCurrentUser).toHaveBeenCalled());
  });
});
