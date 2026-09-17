import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';

import { DEFAULT_TIMEOUT_MS, EXPORT_TIMEOUT_MS, http } from '../http';

/**
 * A signed-in person is logged out only when their session is actually gone. QA saw a single
 * 429 (rate limit) and a single 502 (an API restart) on the refresh call each throw the user to
 * the sign-in screen.
 */
describe('http client session refresh', () => {
  type Reply = { status: number; data?: unknown };
  let refreshReplies: Reply[];
  let refreshCalls: number;
  let onLogout: Mock<() => void>;
  const client = (http as unknown as { client: { defaults: { adapter: unknown } } }).client;
  let originalAdapter: unknown;

  const respond = (config: InternalAxiosRequestConfig, reply: Reply): Promise<AxiosResponse> => {
    const response = { data: reply.data ?? {}, status: reply.status, statusText: '', headers: {}, config } as AxiosResponse;
    if (reply.status >= 400) {
      return Promise.reject(Object.assign(new Error(`HTTP ${reply.status}`), { config, response, isAxiosError: true }));
    }
    return Promise.resolve(response);
  };

  beforeEach(() => {
    vi.useFakeTimers();
    refreshCalls = 0;
    onLogout = vi.fn<() => void>();
    http.setOnLogout(onLogout);
    http.setToken('expired');
    originalAdapter = client.defaults.adapter;
    const adapter: AxiosAdapter = (config) => {
      if (config.url?.includes('/api/v1/auth/refresh')) {
        refreshCalls += 1;
        return respond(config, refreshReplies.shift() ?? { status: 401 });
      }
      const authorized = config.headers?.Authorization === 'Bearer fresh';
      return respond(config, authorized ? { status: 200, data: { ok: true } } : { status: 401 });
    };
    client.defaults.adapter = adapter;
  });

  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    http.setOnLogout(null);
    http.setToken(null);
    vi.useRealTimers();
  });

  it('retries a refresh that hit a rate limit, and keeps the person signed in', async () => {
    refreshReplies = [{ status: 429 }, { status: 200, data: { accessToken: 'fresh' } }];
    const result = http.get<{ ok: boolean }>('/api/v1/projects');
    await vi.runAllTimersAsync();
    await expect(result).resolves.toEqual({ ok: true });
    expect(refreshCalls).toBe(2);
    expect(onLogout).not.toHaveBeenCalled();
  });

  it('retries a refresh that met a restarting API (502)', async () => {
    refreshReplies = [{ status: 502 }, { status: 200, data: { accessToken: 'fresh' } }];
    const result = http.get<{ ok: boolean }>('/api/v1/projects');
    await vi.runAllTimersAsync();
    await expect(result).resolves.toEqual({ ok: true });
    expect(onLogout).not.toHaveBeenCalled();
  });

  it.each([500, 504])(
    'does not replay a refresh that may have rotated the cookie before failing (%i)',
    async (status) => {
      // The API may have rotated the refresh token and then failed to answer. The browser never
      // saw the new cookie, so a retry would present the rotated-away one: reuse detection then
      // revokes every session the person has.
      refreshReplies = [{ status }, { status: 200, data: { accessToken: 'fresh' } }];
      const result = http.get('/api/v1/projects');
      const settled = expect(result).rejects.toBeTruthy();
      await vi.runAllTimersAsync();
      await settled;
      expect(refreshCalls).toBe(1);
      expect(onLogout).not.toHaveBeenCalled();
    },
  );

  it('does not log out when refresh keeps failing for a transient reason', async () => {
    refreshReplies = [{ status: 503 }, { status: 503 }, { status: 503 }, { status: 503 }];
    const result = http.get('/api/v1/projects');
    const settled = expect(result).rejects.toBeTruthy();
    await vi.runAllTimersAsync();
    await settled;
    expect(refreshCalls).toBeLessThanOrEqual(3);
    expect(onLogout).not.toHaveBeenCalled();
  });

  it('logs out when the session itself is rejected (401)', async () => {
    refreshReplies = [{ status: 401 }];
    const result = http.get('/api/v1/projects');
    const settled = expect(result).rejects.toBeTruthy();
    await vi.runAllTimersAsync();
    await settled;
    expect(refreshCalls).toBe(1);
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('fails the requests queued behind a refresh that fails, instead of leaving them pending forever', async () => {
    refreshReplies = [{ status: 401 }];
    const first = http.get('/api/v1/projects');
    const queued = http.get('/api/v1/endpoints');
    const outcome = (p: Promise<unknown>) => p.then(() => 'resolved', () => 'rejected');
    const firstOutcome = outcome(first);
    const queuedOutcome = outcome(queued);
    await vi.runAllTimersAsync();

    expect(await firstOutcome).toBe('rejected');
    const pending = Symbol('pending');
    expect(await Promise.race([queuedOutcome, Promise.resolve().then(() => pending)])).toBe('rejected');
    expect(refreshCalls).toBe(1);
  });
});

/**
 * Tabs share one refresh cookie, and the API revokes every session when a rotated-away cookie is
 * presented again. Two tabs refreshing at once (a browser restoring its tabs, a laptop waking up)
 * sent the same cookie twice and signed the person out everywhere. A refresh now waits for any
 * other tab's to finish, so it goes out with the cookie that one left in the jar.
 */
describe('http client refresh across tabs', () => {
  const client = (http as unknown as { client: { defaults: { adapter: unknown } } }).client;
  let originalAdapter: unknown;
  let refreshCalls: number;
  let releaseOtherTab: () => void;
  const lockNames: string[] = [];

  /** Web Locks as a browser grants them: one holder per name, the rest queued in order. */
  function installLocks() {
    let tail: Promise<unknown> = new Promise<void>((resolve) => { releaseOtherTab = resolve; });
    const locks = {
      request: (name: string, callback: () => Promise<unknown>) => {
        lockNames.push(name);
        const run = tail.then(() => callback());
        tail = run.catch(() => undefined);
        return run;
      },
    };
    Object.defineProperty(navigator, 'locks', { value: locks, configurable: true });
  }

  beforeEach(() => {
    vi.useFakeTimers();
    refreshCalls = 0;
    lockNames.length = 0;
    http.setToken('expired');
    originalAdapter = client.defaults.adapter;
    const adapter: AxiosAdapter = (config) => {
      const reply = (status: number, data: unknown = {}) => {
        const response = { data, status, statusText: '', headers: {}, config } as AxiosResponse;
        return status >= 400
          ? Promise.reject(Object.assign(new Error(`HTTP ${status}`), { config, response, isAxiosError: true }))
          : Promise.resolve(response);
      };
      if (config.url?.includes('/api/v1/auth/refresh')) {
        refreshCalls += 1;
        return reply(200, { accessToken: 'fresh' });
      }
      return config.headers?.Authorization === 'Bearer fresh' ? reply(200, { ok: true }) : reply(401);
    };
    client.defaults.adapter = adapter;
  });

  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    http.setToken(null);
    delete (navigator as { locks?: unknown }).locks;
    vi.useRealTimers();
  });

  it('does not send its refresh while another tab holds the refresh lock', async () => {
    installLocks();
    const result = http.get<{ ok: boolean }>('/api/v1/projects');
    await vi.advanceTimersByTimeAsync(100);
    expect(refreshCalls).toBe(0);

    releaseOtherTab();
    await vi.runAllTimersAsync();

    await expect(result).resolves.toEqual({ ok: true });
    expect(refreshCalls).toBe(1);
    expect(lockNames).toEqual(['railhook-auth-refresh']);
  });

  it('shares one refresh between a page-load restore and a request that met a 401', async () => {
    const restored = http.refreshSession();
    const request = http.get<{ ok: boolean }>('/api/v1/projects');
    await vi.runAllTimersAsync();

    await expect(restored).resolves.toBe('fresh');
    await expect(request).resolves.toEqual({ ok: true });
    expect(refreshCalls).toBe(1);
  });

  it('still refreshes in a browser without Web Locks', async () => {
    const result = http.get<{ ok: boolean }>('/api/v1/projects');
    await vi.runAllTimersAsync();
    await expect(result).resolves.toEqual({ ok: true });
    expect(refreshCalls).toBe(1);
  });
});

/**
 * A request with no timeout never settles when the backend stops answering, so the page it
 * belongs to spins forever: no error state, nothing for react-query to catch, no way out but
 * a reload. The client shipped without one.
 */
describe('http client timeouts', () => {
  it('has a finite default', () => {
    expect(DEFAULT_TIMEOUT_MS).toBeGreaterThan(0);
    expect(Number.isFinite(DEFAULT_TIMEOUT_MS)).toBe(true);
  });

  it('gives exports longer than an ordinary call, since they stream a whole dataset', () => {
    expect(EXPORT_TIMEOUT_MS).toBeGreaterThan(DEFAULT_TIMEOUT_MS);
  });

  it('keeps the default short enough to surface a hung backend while someone is still watching', () => {
    expect(DEFAULT_TIMEOUT_MS).toBeLessThanOrEqual(60_000);
  });
});
