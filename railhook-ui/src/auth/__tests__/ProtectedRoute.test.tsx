import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import ProtectedRoute, { hasMinRole } from '../ProtectedRoute';
import { AuthContext, type AuthState } from '../auth.store';
import type { CurrentUserResponse } from '../../types/api.types';

/**
 * The gate in front of every admin route, and until now the least tested code in the app.
 * What it decides is who sees what, so the cases below are the ones where being wrong is not
 * a cosmetic bug: an unauthenticated visitor reaching a page, or a Viewer reaching an Owner's.
 */
describe('ProtectedRoute', () => {
  function authState(overrides: Partial<AuthState> & { role?: string } = {}): AuthState {
    const { role, ...rest } = overrides;
    const user = role
      ? ({
          user: { id: 'u1', email: 'someone@example.com', fullName: 'Someone', status: 'ACTIVE' },
          organization: { id: 'o1', name: 'Org', createdAt: new Date().toISOString() },
          role,
        } as unknown as CurrentUserResponse)
      : null;
    return {
      user,
      token: role ? 'a-token' : null,
      login: () => {},
      logout: () => {},
      updateUser: () => {},
      isAuthenticated: Boolean(role),
      ...rest,
    };
  }

  function renderGuarded(state: AuthState, requiredRole?: 'OWNER' | 'DEVELOPER' | 'VIEWER') {
    return render(
      <AuthContext.Provider value={state}>
        <MemoryRouter initialEntries={['/admin/secret']}>
          <Routes>
            <Route
              path="/admin/secret"
              element={<ProtectedRoute requiredRole={requiredRole}><p>the guarded page</p></ProtectedRoute>}
            />
            <Route path="/login" element={<p>the sign-in screen</p>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    );
  }

  it('sends an unauthenticated visitor to sign in, and does not render the page first', () => {
    renderGuarded(authState());

    expect(screen.getByText('the sign-in screen')).toBeInTheDocument();
    expect(screen.queryByText('the guarded page')).not.toBeInTheDocument();
  });

  it('lets an authenticated user through when no role is demanded', () => {
    renderGuarded(authState({ role: 'VIEWER' }));

    expect(screen.getByText('the guarded page')).toBeInTheDocument();
  });

  it('refuses a Viewer a page that requires a Developer', () => {
    renderGuarded(authState({ role: 'VIEWER' }), 'DEVELOPER');

    expect(screen.queryByText('the guarded page')).not.toBeInTheDocument();
  });

  it('refuses a Developer a page that requires the Owner', () => {
    renderGuarded(authState({ role: 'DEVELOPER' }), 'OWNER');

    expect(screen.queryByText('the guarded page')).not.toBeInTheDocument();
  });

  it('lets the Owner everywhere', () => {
    renderGuarded(authState({ role: 'OWNER' }), 'OWNER');

    expect(screen.getByText('the guarded page')).toBeInTheDocument();
  });

  it('treats a user whose role is missing as the least privileged', () => {
    // A session restored from an older shape, or a backend that stopped sending it. Defaulting
    // upwards here would hand an Owner's screens to whoever had the gap.
    const state = authState({ role: 'OWNER' });
    (state.user as unknown as { role?: string }).role = undefined;

    renderGuarded(state, 'DEVELOPER');

    expect(screen.queryByText('the guarded page')).not.toBeInTheDocument();
  });

  describe('hasMinRole', () => {
    it('orders the three roles', () => {
      expect(hasMinRole('OWNER', 'VIEWER')).toBe(true);
      expect(hasMinRole('DEVELOPER', 'VIEWER')).toBe(true);
      expect(hasMinRole('VIEWER', 'DEVELOPER')).toBe(false);
      expect(hasMinRole('DEVELOPER', 'OWNER')).toBe(false);
    });

    it('counts a role as meeting itself', () => {
      expect(hasMinRole('VIEWER', 'VIEWER')).toBe(true);
      expect(hasMinRole('OWNER', 'OWNER')).toBe(true);
    });
  });
});
