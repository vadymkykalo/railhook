import { describe, it, expect, vi, beforeEach } from 'vitest';
import { waitFor } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type { CurrentUserResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { list: vi.fn().mockResolvedValue([]), get: vi.fn() },
}));
vi.mock('../../api/auth.api', () => ({
  authApi: { getCurrentUser: vi.fn(), logout: vi.fn(), resendVerification: vi.fn() },
}));

import AppLayout from '../../layout/AppLayout';
import { authApi } from '../../api/auth.api';

const STORED: CurrentUserResponse = {
  user: { id: 'user-1', email: 'owner@example.com', fullName: 'Owner', status: 'ACTIVE' },
  organization: { id: 'org-1', name: 'Test Org', createdAt: new Date().toISOString() },
  role: 'OWNER',
  emailDeliveryEnabled: false,
  hasPassword: true,
  platformAdmin: false,
};

/**
 * The layout asks the server who the person is on every navigation, and used to keep the answer
 * only when the account status or the platform-admin flag had changed. A demotion, or an
 * organization switched in another tab, left the stored copy — and every role check that reads
 * it — describing someone the server no longer recognised.
 */
describe('the signed-in user the layout keeps', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  function renderWith(queryClient = new QueryClient()) {
    const updateUser = vi.fn();
    renderPage(<AppLayout />, {
      path: '/admin/*', initialEntry: '/admin/dashboard', queryClient, auth: { user: STORED, updateUser },
    });
    return updateUser;
  }

  it('takes a changed role from the server', async () => {
    const demoted = { ...STORED, role: 'VIEWER' } as CurrentUserResponse;
    vi.mocked(authApi.getCurrentUser).mockResolvedValue(demoted);

    const updateUser = renderWith();

    await waitFor(() => expect(updateUser).toHaveBeenCalledWith(demoted));
  });

  it('takes a changed organization from the server, and drops what was cached for the old one', async () => {
    const elsewhere = {
      ...STORED, organization: { id: 'org-2', name: 'Client Co', createdAt: new Date().toISOString() },
    } as CurrentUserResponse;
    vi.mocked(authApi.getCurrentUser).mockResolvedValue(elsewhere);
    const queryClient = new QueryClient();
    queryClient.setQueryData(['audit-log'], [{ id: 'from-org-1' }]);

    const updateUser = renderWith(queryClient);

    await waitFor(() => expect(updateUser).toHaveBeenCalledWith(elsewhere));
    expect(queryClient.getQueryData(['audit-log'])).toBeUndefined();
  });

  it('leaves the cache alone when nothing about the person changed', async () => {
    vi.mocked(authApi.getCurrentUser).mockResolvedValue({ ...STORED });
    const queryClient = new QueryClient();
    queryClient.setQueryData(['audit-log'], [{ id: 'kept' }]);

    const updateUser = renderWith(queryClient);

    await waitFor(() => expect(authApi.getCurrentUser).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(updateUser).not.toHaveBeenCalled();
    expect(queryClient.getQueryData(['audit-log'])).toEqual([{ id: 'kept' }]);
  });
});
