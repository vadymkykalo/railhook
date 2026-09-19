import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';
import type { CurrentUserResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { list: vi.fn().mockResolvedValue([]), get: vi.fn() },
}));
vi.mock('../../api/auth.api', () => ({
  authApi: { getCurrentUser: vi.fn().mockResolvedValue(null), logout: vi.fn(), resendVerification: vi.fn() },
}));

import AppLayout from '../../layout/AppLayout';

const DEMO: CurrentUserResponse = {
  user: { id: 'demo-user', email: 'visitor@demo.railhook.invalid', fullName: 'Demo visitor', status: 'ACTIVE' },
  organization: { id: 'demo-org', name: 'Acme Inc.', createdAt: new Date().toISOString() },
  role: 'VIEWER',
  emailDeliveryEnabled: false,
  hasPassword: false,
  platformAdmin: false,
  demo: true,
};

/** Where the layout sends a demo visitor, rendered so a test can see it arrived. */
function WithDestinations() {
  return (
    <Routes>
      <Route path="/admin/*" element={<AppLayout />} />
      <Route path="/" element={<p>landing</p>} />
      <Route path="/register" element={<p>register</p>} />
    </Routes>
  );
}

/**
 * The live demo's dashboard says what it is on every page, and every way out of it ends the demo
 * session: signing up, or leaving for the public site — never the sign-in page, since a demo
 * visitor has no account to sign in to.
 */
describe('the dashboard in the live demo', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('says it is a read-only demo, and offers an account of your own', () => {
    renderPage(<AppLayout />, { path: '/admin/*', initialEntry: '/admin/dashboard', auth: { user: DEMO } });

    expect(screen.getByText(en.demo.banner)).toBeInTheDocument();
    expect(screen.getByText(en.demo.bannerBody)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.demo.startFree })).toBeInTheDocument();
  });

  it('shows no banner to a real account', () => {
    renderPage(<AppLayout />, { path: '/admin/*', initialEntry: '/admin/dashboard' });

    expect(screen.queryByText(en.demo.banner)).toBeNull();
  });

  it('ends the demo and opens registration from "Start free"', async () => {
    const logout = vi.fn();
    renderPage(<WithDestinations />, { path: '/*', initialEntry: '/admin/dashboard', auth: { user: DEMO, logout } });

    await userEvent.click(screen.getByRole('button', { name: en.demo.startFree }));

    expect(logout).toHaveBeenCalled();
    expect(await screen.findByText('register')).toBeInTheDocument();
  });

  it('returns to the public site on leaving, not to the sign-in page', async () => {
    const logout = vi.fn();
    renderPage(<WithDestinations />, { path: '/*', initialEntry: '/admin/dashboard', auth: { user: DEMO, logout } });

    await userEvent.click(screen.getByRole('button', { name: en.demo.exit }));

    expect(logout).toHaveBeenCalled();
    expect(await screen.findByText('landing')).toBeInTheDocument();
  });
});
