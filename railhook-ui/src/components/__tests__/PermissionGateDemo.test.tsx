import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import PermissionGate from '../PermissionGate';
import { AuthContext, type AuthState } from '../../auth/auth.store';
import type { CurrentUserResponse } from '../../types/api.types';

const VIEWER: CurrentUserResponse = {
  user: { id: 'u', email: 'v@example.com', fullName: 'V', status: 'ACTIVE' },
  organization: { id: 'o', name: 'O', createdAt: new Date().toISOString() },
  role: 'VIEWER',
  emailDeliveryEnabled: false,
  hasPassword: true,
  platformAdmin: false,
};

function renderGate(user: CurrentUserResponse) {
  const auth: AuthState = {
    user, token: 't', isAuthenticated: true, login: () => {}, logout: () => {}, updateUser: () => {},
  };
  render(
    <AuthContext.Provider value={auth}>
      <PermissionGate allowed={false}>
        <button type="button">New endpoint</button>
      </PermissionGate>
    </AuthContext.Provider>,
  );
}

/**
 * In the live demo every action is greyed out, and the reason given is the demo — not a role, which
 * would send a visitor looking for a teammate who does not exist.
 */
describe('PermissionGate in the live demo', () => {
  it('disables the action and says the demo is read-only', async () => {
    renderGate({ ...VIEWER, demo: true });

    const button = screen.getByRole('button', { name: 'New endpoint' });
    expect(button).toBeDisabled();
    await userEvent.hover(button.parentElement as HTMLElement);
    expect((await screen.findAllByText(en.demo.readOnlyTooltip)).length).toBeGreaterThan(0);
  });

  it('still names the role for a real Viewer', async () => {
    renderGate(VIEWER);

    const button = screen.getByRole('button', { name: 'New endpoint' });
    await userEvent.hover(button.parentElement as HTMLElement);
    expect(screen.queryByText(en.demo.readOnlyTooltip)).toBeNull();
  });
});
