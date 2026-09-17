import { http } from './http';
import type {
  RegisterRequest, LoginRequest, AuthResponse, CurrentUserResponse, ChangeEmailRequest, EmailChangeResponse,
} from '../types/api.types';

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

  /** Which identity providers this deployment offers. Public: the sign-in page asks first. */
  providers: (): Promise<{ google: boolean }> => {
    return http.get<{ google: boolean }>('/api/v1/auth/providers');
  },

  /**
   * Trades the one-time code the Google callback put in the URL for a session, exactly like a
   * password sign-in: access token in the body, refresh token in its cookie. Works once.
   */
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

  /** The account's address and the change waiting for confirmation, if any. */
  getEmailChange: (): Promise<EmailChangeResponse> => {
    return http.get<EmailChangeResponse>('/api/v1/auth/email-change');
  },

  /**
   * An unverified account moves at once (answering the CAPTCHA when one is configured); a verified
   * one sends its password and waits for the new address to confirm.
   */
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

  /** "This wasn't me", from the notice sent to the old address. */
  cancelEmailChangeByToken: (token: string): Promise<void> => {
    return http.post<void>(`/api/v1/auth/email-change/cancel?token=${encodeURIComponent(token)}`);
  },

  changePassword: (currentPassword: string, newPassword: string): Promise<void> => {
    return http.post<void>('/api/v1/auth/change-password', { currentPassword, newPassword });
  },

  /** Takes the token to end explicitly: the caller clears the client's own before this is sent. */
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

  /**
   * GDPR Article 17, for the signed-in person. Irreversible: the account's personal data is
   * replaced, every session is closed, and any organization they were the only member of is
   * deleted with it. Answers 409 when they are the last owner of an organization that still
   * has other members.
   */
  eraseOwnAccount: (): Promise<void> => {
    return http.delete<void>('/api/v1/auth/me');
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
