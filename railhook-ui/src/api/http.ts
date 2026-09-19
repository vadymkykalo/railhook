import axios, { AxiosInstance, AxiosError, InternalAxiosRequestConfig } from 'axios';

const API_URL = import.meta.env.VITE_API_URL || '';

/** Long enough for any ordinary call, short enough that a hung backend surfaces as an error. */
export const DEFAULT_TIMEOUT_MS = 30_000;

/** Exports stream a whole dataset; they are the one call that may legitimately outlast the rest. */
export const EXPORT_TIMEOUT_MS = 120_000;

type OnLogoutCallback = () => void;

/** Waits before retrying a refresh that failed for a reason that is not the session itself. */
export const REFRESH_RETRY_DELAYS_MS = [1_000, 3_000];

function statusOf(err: unknown): number | undefined {
  return (err as AxiosError | undefined)?.response?.status;
}

/** The server looked at the session and refused it: signing out is the right answer. */
function isSessionRejected(err: unknown): boolean {
  const status = statusOf(err);
  return status === 401 || status === 403;
}

/**
 * Worth sending the same refresh cookie again: the API cannot have rotated it. A rate limit is
 * refused before the token is looked at; a gateway that could not reach the API (502, 503) or a
 * connection that failed outright never delivered the request.
 *
 * Not a 500, a 504 or a timeout. The API may have rotated the token and then failed to answer,
 * and a retry would present the rotated-away cookie, which reuse detection answers by revoking
 * every session the person has. Such a failure is left to surface as an error without signing
 * anyone out. The residual risk is a 502 or a dropped connection that did reach the API, which
 * the browser cannot tell apart from one that did not.
 */
function isRetryableRefreshFailure(err: unknown): boolean {
  const status = statusOf(err);
  if (status === undefined) {
    return (err as AxiosError | undefined)?.code !== 'ECONNABORTED'
      && (err as AxiosError | undefined)?.code !== 'ETIMEDOUT';
  }
  return status === 429 || status === 502 || status === 503;
}

/** Held by whichever tab is refreshing, so no two tabs present the same cookie at once. */
export const REFRESH_LOCK_NAME = 'railhook-auth-refresh';

class HttpClient {
  private client: AxiosInstance;
  private token: string | null = null;
  private refreshInFlight: Promise<string> | null = null;
  private onLogout: OnLogoutCallback | null = null;
  /** A live-demo session: an access token with nothing to refresh it from. */
  private demo = false;

  constructor() {
    this.client = axios.create({
      baseURL: API_URL,
      headers: {
        'Content-Type': 'application/json',
      },
      withCredentials: true, // Send cookies with requests
      // Without this a request that never answers never settles, and the page it belongs to
      // spins until someone reloads: no error state, no retry, nothing for react-query to
      // catch. 30s is generous for an ordinary call and still finite. getBlob raises it —
      // an export is the one thing here that legitimately takes longer.
      timeout: DEFAULT_TIMEOUT_MS,
    });

    this.client.interceptors.request.use((config) => {
      if (this.token) {
        config.headers.Authorization = `Bearer ${this.token}`;
      }
      return config;
    });

    this.client.interceptors.response.use(
      (response) => response,
      async (error: AxiosError) => {
        const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean };

        if (error.response?.status === 401 && this.demo) {
          // The demo's token has expired, and there is no refresh token behind it: presenting
          // this browser's refresh cookie would sign a demo tab into whoever uses it for real.
          this.token = null;
          this.demo = false;
          if (this.onLogout) {
            this.onLogout();
          }
          return Promise.reject(error);
        }

        if (
          error.response?.status === 401 &&
          !originalRequest._retry &&
          !originalRequest.url?.includes('/api/v1/auth/refresh') &&
          !originalRequest.url?.includes('/api/v1/auth/login') &&
          // A sign-out refused is a session already over; refreshing would bring it back.
          !originalRequest.url?.includes('/api/v1/auth/logout')
        ) {
          originalRequest._retry = true;
          const accessToken = await this.refreshSession();
          originalRequest.headers.Authorization = `Bearer ${accessToken}`;
          return this.client(originalRequest);
        }

        return Promise.reject(error);
      }
    );
  }

  /**
   * One refresh at a time, shared by every request that met a 401 while it runs. Each of them
   * settles with it: a queue that was only ever resolved left the requests behind a failed
   * refresh pending forever, with their buttons disabled and their spinners turning.
   */
  refreshSession(): Promise<string> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.underRefreshLock(() => this.runRefresh()).finally(() => {
        this.refreshInFlight = null;
      });
    }
    return this.refreshInFlight;
  }

  /**
   * Tabs share the refresh cookie, and each refresh rotates it. Two tabs refreshing together (a
   * browser restoring its tabs, a laptop waking) sent the same cookie twice, and the API took the
   * second for a stolen token and signed the person out everywhere. Under the lock the second tab
   * goes after the first has finished, with the cookie the first one left in the shared jar, and
   * the access token the first tab holds stays valid: rotation does not revoke access tokens.
   * Without Web Locks the refresh runs as it did before.
   */
  private underRefreshLock<T>(work: () => Promise<T>): Promise<T> {
    const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
    if (!locks?.request) {
      return work();
    }
    return locks.request(REFRESH_LOCK_NAME, work) as Promise<T>;
  }

  private async runRefresh(): Promise<string> {
    try {
      const response = await this.refreshWithRetry();
      const { accessToken } = response.data;
      this.token = accessToken;
      return accessToken;
    } catch (refreshError) {
      // Log out only when the session itself was refused. A rate limit, an API restart or a
      // dropped connection is not the end of a session, and treating it as one threw people
      // to the sign-in screen for browsing quickly or for a deploy.
      if (isSessionRejected(refreshError)) {
        this.token = null;
        localStorage.removeItem('auth_user');
        if (this.onLogout) {
          this.onLogout();
        }
      }
      throw refreshError;
    }
  }

  private async refreshWithRetry() {
    for (let attempt = 0; ; attempt++) {
      try {
        return await this.client.post('/api/v1/auth/refresh', {});
      } catch (err) {
        if (!isRetryableRefreshFailure(err) || attempt >= REFRESH_RETRY_DELAYS_MS.length) {
          throw err;
        }
        await new Promise((resolve) => setTimeout(resolve, REFRESH_RETRY_DELAYS_MS[attempt]));
      }
    }
  }

  setToken(token: string | null) {
    this.token = token;
  }

  /** Marks the session as the live demo's, whose token must never be refreshed. */
  setDemo(demo: boolean) {
    this.demo = demo;
  }

  isDemo(): boolean {
    return this.demo;
  }

  getToken(): string | null {
    return this.token;
  }

  setOnLogout(callback: OnLogoutCallback | null) {
    this.onLogout = callback;
  }

  async get<T>(url: string): Promise<T> {
    const response = await this.client.get<T>(url);
    return response.data;
  }

  async getBlob(url: string): Promise<Blob> {
    const response = await this.client.get(url, { responseType: 'blob', timeout: EXPORT_TIMEOUT_MS });
    return response.data;
  }

  async post<T>(url: string, data?: unknown, headers?: Record<string, string>): Promise<T> {
    const response = await this.client.post<T>(url, data, { headers });
    return response.data;
  }

  async put<T>(url: string, data?: unknown): Promise<T> {
    const response = await this.client.put<T>(url, data);
    return response.data;
  }

  async patch<T>(url: string, data?: unknown): Promise<T> {
    const response = await this.client.patch<T>(url, data);
    return response.data;
  }

  async delete<T>(url: string): Promise<T> {
    const response = await this.client.delete<T>(url);
    return response.data;
  }
}

export const http = new HttpClient();
