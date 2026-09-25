import { http } from './http';
import type {
  RegisterRequest, LoginRequest, AuthResponse, CurrentUserResponse, ChangeEmailRequest, EmailChangeResponse,
} from '../types/api.types';

export interface SessionResponse {
  id: string;
  client: 'WEB' | 'CLI';
  userAgent: string | null;
  ipAddress: string | null;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  current: boolean;
}

export const authApi = {
  register: (data: RegisterRequest): Promise<AuthResponse> => {
    return http.post<AuthResponse>('/api/v1/auth/register', data);
  },

  login: (data: LoginRequest): Promise<AuthResponse> => {
    return http.post<AuthResponse>('/api/v1/auth/login', data);
  },

  providers: (): Promise<{ google: boolean }> => {
    return http.get<{ google: boolean }>('/api/v1/auth/providers');
  },

  exchangeSignInCode: (code: string): Promise<AuthResponse> => {
    return http.post<AuthResponse>('/api/v1/auth/oauth/exchange', { code });
  },

  getCurrentUser: (): Promise<CurrentUserResponse> => {
    return http.get<CurrentUserResponse>('/api/v1/auth/me');
  },

  verifyEmail: (token: string): Promise<void> => {
    return http.post<void>(`/api/v1/auth/verify-email?token=${encodeURIComponent(token)}`);
  },

  resendVerification: (email: string): Promise<void> => {
    return http.post<void>(`/api/v1/auth/resend-verification?email=${encodeURIComponent(email)}`);
  },

  getEmailChange: (): Promise<EmailChangeResponse> => {
    return http.get<EmailChangeResponse>('/api/v1/auth/email-change');
  },

  requestEmailChange: (data: ChangeEmailRequest): Promise<EmailChangeResponse> => {
    return http.post<EmailChangeResponse>('/api/v1/auth/email-change', data);
  },

  resendEmailChange: (): Promise<EmailChangeResponse> => {
    return http.post<EmailChangeResponse>('/api/v1/auth/email-change/resend', {});
  },

  cancelEmailChange: (): Promise<void> => {
    return http.delete<void>('/api/v1/auth/email-change');
  },

  confirmEmailChange: (token: string): Promise<void> => {
    return http.post<void>(`/api/v1/auth/email-change/confirm?token=${encodeURIComponent(token)}`);
  },

  cancelEmailChangeByToken: (token: string): Promise<void> => {
    return http.post<void>(`/api/v1/auth/email-change/cancel?token=${encodeURIComponent(token)}`);
  },

  changePassword: (currentPassword: string, newPassword: string): Promise<void> => {
    return http.post<void>('/api/v1/auth/change-password', { currentPassword, newPassword });
  },

  /** The caller clears the client's own token before this is sent. */
  logout: (accessToken: string | null): Promise<void> => {
    return http.post<void>('/api/v1/auth/logout', {}, accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined);
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

  eraseOwnAccount: (): Promise<void> => {
    return http.delete<void>('/api/v1/auth/me');
  },

  /** The refresh cookie is untouched, so a double-click isn't a token-reuse alarm. */
  switchOrganization: (organizationId: string): Promise<AuthResponse> => {
    return http.post<AuthResponse>('/api/v1/auth/switch-organization', { organizationId });
  },

  updateProfile: (data: { fullName?: string }): Promise<{ id: string; email: string; fullName: string | null; status: string }> => {
    return http.put('/api/v1/auth/profile', data);
  },
};
