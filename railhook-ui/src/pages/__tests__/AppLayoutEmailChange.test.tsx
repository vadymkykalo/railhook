import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type { CurrentUserResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { list: vi.fn().mockResolvedValue([]), get: vi.fn() },
}));
vi.mock('../../api/auth.api', () => ({
  authApi: {
    getCurrentUser: vi.fn().mockResolvedValue(null),
    logout: vi.fn(),
    resendVerification: vi.fn(),
    requestEmailChange: vi.fn(),
  },
}));

import AppLayout from '../../layout/AppLayout';
import { authApi } from '../../api/auth.api';

const UNVERIFIED = {
  user: { id: 'user-1', email: 'wheelet1228@gmail.con', fullName: 'Wheelet', status: 'PENDING_VERIFICATION' },
  organization: { id: 'org-1', name: 'Wheelet Org', createdAt: new Date().toISOString() },
  role: 'OWNER',
  emailDeliveryEnabled: true,
  hasPassword: true,
} as unknown as CurrentUserResponse;

describe('the verification banner', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lets an unverified account fix its address in place and sends a new link there', async () => {
    const updateUser = vi.fn();
    vi.mocked(authApi.requestEmailChange).mockResolvedValue({ email: 'wheelet1228@gmail.com', applied: true });
    const user = userEvent.setup();
    renderPage(<AppLayout />, {
      path: '/admin/*', initialEntry: '/admin/dashboard', auth: { user: UNVERIFIED, updateUser },
    });

    await user.click(await screen.findByRole('button', { name: /wrong address\? change it/i }));
    const field = screen.getByLabelText(/new email address/i);
    expect(field).toHaveValue('wheelet1228@gmail.con');

    await user.click(screen.getByRole('button', { name: 'wheelet1228@gmail.com' }));
    await user.click(screen.getByRole('button', { name: /save and send link/i }));

    await waitFor(() => expect(authApi.requestEmailChange).toHaveBeenCalledWith({ newEmail: 'wheelet1228@gmail.com' }));
    expect(updateUser).toHaveBeenCalledWith(expect.objectContaining({
      user: expect.objectContaining({ email: 'wheelet1228@gmail.com', status: 'PENDING_VERIFICATION' }),
    }));
    expect(screen.queryByLabelText(/new email address/i)).not.toBeInTheDocument();
  });

  it('shows why the API refused, and keeps the form open', async () => {
    vi.mocked(authApi.requestEmailChange).mockRejectedValue({
      response: { status: 409, data: { message: 'Email already exists' } },
    });
    const user = userEvent.setup();
    renderPage(<AppLayout />, { path: '/admin/*', initialEntry: '/admin/dashboard', auth: { user: UNVERIFIED } });

    await user.click(await screen.findByRole('button', { name: /wrong address\? change it/i }));
    const field = screen.getByLabelText(/new email address/i);
    await user.clear(field);
    await user.type(field, 'taken@example.com');
    await user.click(screen.getByRole('button', { name: /save and send link/i }));

    expect(await screen.findByText('Email already exists')).toBeInTheDocument();
    expect(screen.getByLabelText(/new email address/i)).toBeInTheDocument();
  });
});
