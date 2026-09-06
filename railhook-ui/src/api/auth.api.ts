import { http } from './http';
import type { RegisterRequest, LoginRequest, AuthResponse, CurrentUserResponse } from '../types/api.types';

/** One signed-in device, as the sessions list shows it. Carries no token material. */
export interface SessionResponse {
  id: string;
  /** WEB for a browser sign-in, CLI for a device-code grant from the command line. */
  client: 'WEB' | 'CLI';
  userAgent: string | null;
  ipAddress: string | null;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  /** The session making this request — never offered for revocation as if it were another. */
  current: boolean;
}

export const authApi = {
  register: (data: RegisterRequest): Promise<AuthResponse> => {
    return http.post<AuthResponse>('/api/v1/auth/register', data);
  },

  login: (data: LoginRequest): Promise<AuthResponse> => {
    return http.post<AuthResponse>('/api/v1/auth/login', data);
  },

  getCurrentUser: (): Promise<CurrentUserResponse> => {
    return http.get<CurrentUserResponse>('/api/v1/auth/me');
  },

  refresh: (): Promise<AuthResponse> => {
    return http.post<AuthResponse>('/api/v1/auth/refresh', {});
  },

  verifyEmail: (token: string): Promise<void> => {
    return http.post<void>(`/api/v1/auth/verify-email?token=${encodeURIComponent(token)}`);
  },

  resendVerification: (email: string): Promise<void> => {
    return http.post<void>(`/api/v1/auth/resend-verification?email=${encodeURIComponent(email)}`);
  },

  changePassword: (currentPassword: string, newPassword: string): Promise<void> => {
    return http.post<void>('/api/v1/auth/change-password', { currentPassword, newPassword });
  },

  logout: (): Promise<void> => {
    return http.post<void>('/api/v1/auth/logout', {});
  },

  forgotPassword: (email: string): Promise<void> => {
    return http.post<void>('/api/v1/auth/forgot-password', { email });
  },

  resetPassword: (token: string, newPassword: string): Promise<void> => {
    return http.post<void>('/api/v1/auth/reset-password', { token, newPassword });
  },

  listSessions: (): Promise<SessionResponse[]> => {
    return http.get<SessionResponse[]>('/api/v1/auth/sessions');
  },

  revokeSession: (sessionId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/auth/sessions/${sessionId}`);
  },

  revokeAllSessions: (): Promise<void> => {
    return http.post<void>('/api/v1/auth/sessions/revoke-all', {});
  },

  /**
   * Re-issues an access token scoped to another organization. Returns only an access token:
   * the refresh cookie is deliberately untouched, so switching invalidates nothing and a
   * double-click is the same operation twice rather than a token-reuse alarm.
   */
  switchOrganization: (organizationId: string): Promise<AuthResponse> => {
    return http.post<AuthResponse>('/api/v1/auth/switch-organization', { organizationId });
  },

  updateProfile: (data: { fullName?: string }): Promise<{ id: string; email: string; fullName: string | null; status: string }> => {
    return http.put('/api/v1/auth/profile', data);
  },
};
