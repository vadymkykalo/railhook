import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';
import type { CurrentUserResponse } from '../../types/api.types';

vi.mock('../../api/demo.api', () => ({
  demoApi: { createSession: vi.fn() },
}));
vi.mock('../../api/auth.api', () => ({
  authApi: { getCurrentUser: vi.fn() },
}));

import DemoPage from '../DemoPage';
import { demoApi } from '../../api/demo.api';
import { authApi } from '../../api/auth.api';
import { http } from '../../api/http';

const DEMO_USER: CurrentUserResponse = {
  user: { id: 'demo-user', email: 'visitor@demo.railhook.invalid', fullName: 'Demo visitor', status: 'ACTIVE' },
  organization: { id: 'demo-org', name: 'Acme Inc.', createdAt: new Date().toISOString() },
  role: 'VIEWER',
  emailDeliveryEnabled: false,
  hasPassword: false,
  platformAdmin: false,
  demo: true,
};

function renderDemo(startDemo = vi.fn()) {
  renderPage(
    <Routes>
      <Route path="/demo" element={<DemoPage />} />
      <Route path="/admin/dashboard" element={<p>dashboard</p>} />
    </Routes>,
    {
      path: '/*',
      initialEntry: '/demo',
      auth: { user: null, token: null, isAuthenticated: false, startDemo },
    },
  );
  return startDemo;
}

/**
 * /demo opens a read-only session and hands the visitor to the dashboard, with no form to fill in
 * where the deployment asks no challenge. Where the demo is off it says so rather than failing.
 */
describe('DemoPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    http.setToken(null);
    http.setDemo(false);
  });

  afterEach(() => {
    delete window.__RAILHOOK__;
    http.setToken(null);
    http.setDemo(false);
  });

  it('opens the demo straight away and lands on the dashboard', async () => {
    window.__RAILHOOK__ = { publicDemo: true };
    vi.mocked(demoApi.createSession).mockResolvedValue({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' });
    vi.mocked(authApi.getCurrentUser).mockResolvedValue(DEMO_USER);

    const startDemo = renderDemo();

    expect(await screen.findByText('dashboard')).toBeInTheDocument();
    expect(demoApi.createSession).toHaveBeenCalledTimes(1);
    expect(startDemo).toHaveBeenCalledWith('demo-token', DEMO_USER, '2099-01-01T00:00:00Z');
  });

  it('says what went wrong, and restores the tab, when the demo cannot be opened', async () => {
    window.__RAILHOOK__ = { publicDemo: true };
    vi.mocked(demoApi.createSession).mockResolvedValue({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' });
    vi.mocked(authApi.getCurrentUser).mockRejectedValue(new Error('down'));

    const startDemo = renderDemo();

    expect(await screen.findByRole('button', { name: en.demo.page.retry })).toBeInTheDocument();
    expect(startDemo).not.toHaveBeenCalled();
    expect(http.isDemo()).toBe(false);
    expect(http.getToken()).toBeNull();
  });

  it('asks for nothing where the demo is not enabled, and says so', async () => {
    renderDemo();

    expect(screen.getByText(en.demo.page.disabled)).toBeInTheDocument();
    await waitFor(() => expect(demoApi.createSession).not.toHaveBeenCalled());
  });
});
