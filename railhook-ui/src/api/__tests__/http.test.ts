import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
  let onLogout: ReturnType<typeof vi.fn>;
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
    onLogout = vi.fn();
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
