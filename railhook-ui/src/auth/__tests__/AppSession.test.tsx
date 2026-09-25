import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';

import { http } from '../../api/http';
import { destinationAfterSignIn } from '../../lib/signInDestination';

const seen = vi.hoisted(() => ({ queryClient: null as QueryClient | null }));

vi.mock('../../router', async () => {
  const { createMemoryRouter } = await import('react-router-dom');
  const { useQueryClient } = await import('@tanstack/react-query');
  const { useAuth } = await import('../auth.store');

  function Probe() {
    const { user, isAuthenticated, logout } = useAuth();
    seen.queryClient = useQueryClient();
    return (
      <>
        <p>{isAuthenticated ? `signed in as ${user?.user.email}` : 'signed out'}</p>
        <button onClick={() => logout()}>sign out</button>
      </>
    );
  }

  return { router: createMemoryRouter([{ path: '*', element: <Probe /> }]) };
});

import App from '../../App';

const STORED_USER = {
  user: { id: 'u1', email: 'someone@example.com', fullName: 'Someone', status: 'ACTIVE' },
  organization: { id: 'o1', name: 'Org', createdAt: new Date().toISOString() },
  role: 'OWNER',
};

type Call = { url: string; authorization: unknown };

let overrides: Record<string, number> = {};

describe('App session', () => {
  const client = (http as unknown as { client: { defaults: { adapter: unknown } } }).client;
  let originalAdapter: unknown;
  let calls: Call[];

  const reply = (config: InternalAxiosRequestConfig, status: number, data: unknown = {}): Promise<AxiosResponse> => {
    const response = { data, status, statusText: '', headers: {}, config } as AxiosResponse;
    return status >= 400
      ? Promise.reject(Object.assign(new Error(`HTTP ${status}`), { config, response, isAxiosError: true }))
      : Promise.resolve(response);
  };

  beforeEach(() => {
    calls = [];
    overrides = {};
    localStorage.clear();
    sessionStorage.clear();
    http.setToken(null);
    originalAdapter = client.defaults.adapter;
    const adapter: AxiosAdapter = (config) => {
      calls.push({ url: config.url ?? '', authorization: config.headers?.Authorization });
      const override = Object.entries(overrides).find(([url]) => config.url?.includes(url));
      if (override) {
        return reply(config, override[1]);
      }
      if (config.url?.includes('/api/v1/auth/refresh')) {
        return reply(config, 200, { accessToken: 'restored' });
      }
      return reply(config, 200, {});
    };
    client.defaults.adapter = adapter;
  });

  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    http.setToken(null);
    delete (navigator as { locks?: unknown }).locks;
  });

  it('restores a session on load only once another tab has finished refreshing', async () => {
    let releaseOtherTab!: () => void;
    const otherTab = new Promise<void>((resolve) => { releaseOtherTab = resolve; });
    Object.defineProperty(navigator, 'locks', {
      value: { request: (_name: string, callback: () => Promise<unknown>) => otherTab.then(callback) },
      configurable: true,
    });
    localStorage.setItem('auth_user', JSON.stringify(STORED_USER));

    render(<App />);
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
    expect(calls.filter((c) => c.url.includes('/auth/refresh'))).toHaveLength(0);

    releaseOtherTab();

    expect(await screen.findByText('signed in as someone@example.com')).toBeInTheDocument();
    await waitFor(() => expect(http.getToken()).toBe('restored'));
    expect(calls.filter((c) => c.url.includes('/auth/refresh'))).toHaveLength(1);
  });

  async function renderSignedIn() {
    localStorage.setItem('auth_user', JSON.stringify(STORED_USER));
    render(<App />);
    await screen.findByText('signed in as someone@example.com');
    await waitFor(() => expect(http.getToken()).toBe('restored'));
    calls = [];
  }

  const refreshes = () => calls.filter((c) => c.url.includes('/auth/refresh'));

  it('signs out with the session\'s own token, and does not refresh on a 401 to it', async () => {
    overrides = { '/api/v1/auth/logout': 401 };
    await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'sign out' }));
    await screen.findByText('signed out');
    await waitFor(() => expect(calls.some((c) => c.url.includes('/auth/logout'))).toBe(true));
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });

    expect(calls.find((c) => c.url.includes('/auth/logout'))?.authorization).toBe('Bearer restored');
    expect(refreshes()).toHaveLength(0);
    expect(http.getToken()).toBeNull();
  });

  it('forgets everything cached for the person who signed out', async () => {
    await renderSignedIn();
    seen.queryClient!.setQueryData(['projects'], [{ id: 'p1', name: 'Their project' }]);

    await userEvent.click(screen.getByRole('button', { name: 'sign out' }));
    await screen.findByText('signed out');

    expect(seen.queryClient!.getQueryData(['projects'])).toBeUndefined();
  });

  it.each([
    ['signs out', async () => { await userEvent.click(screen.getByRole('button', { name: 'sign out' })); }],
    ['is signed out by the server', async () => {
      overrides = { '/api/v1/projects': 401, '/api/v1/auth/refresh': 401 };
      await act(async () => { await http.get('/api/v1/projects').catch(() => undefined); });
    }],
  ])('does not hand the page a person %s on to whoever signs in next', async (_, endSession) => {
    await renderSignedIn();

    await endSession();
    await screen.findByText('signed out');

    const from = '/admin/projects/their-project/endpoints';
    expect(destinationAfterSignIn({ redirect: null, from, userId: 'someone-new' }, '/admin/projects')).toBe('/admin/projects');
  });

  it('forgets everything cached when the session ends on its own', async () => {
    await renderSignedIn();
    seen.queryClient!.setQueryData(['audit-log'], [{ id: 'a1' }]);
    overrides = { '/api/v1/projects': 401, '/api/v1/auth/refresh': 401 };

    await act(async () => { await http.get('/api/v1/projects').catch(() => undefined); });
    await screen.findByText('signed out');

    expect(seen.queryClient!.getQueryData(['audit-log'])).toBeUndefined();
  });
});
