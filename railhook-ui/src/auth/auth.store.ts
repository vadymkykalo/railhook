import { createContext, useContext } from 'react';
import type { CurrentUserResponse } from '../types/api.types';

export interface AuthState {
  user: CurrentUserResponse | null;
  token: string | null;
  login: (token: string, user: CurrentUserResponse) => void;
  logout: () => void;
  updateUser: (user: CurrentUserResponse) => void;
  isAuthenticated: boolean;
  /**
   * Signs this tab in to the live demo. Unlike `login` it stores nothing a reload would present
   * to the refresh endpoint: the demo has an access token only. Optional so that test harnesses
   * which never start a demo need not provide it.
   */
  startDemo?: (token: string, user: CurrentUserResponse, expiresAt: string) => void;
}

export const AuthContext = createContext<AuthState | undefined>(undefined);

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
};

/**
 * Whether this session is the read-only live demo. False outside an auth provider, so a shared
 * component can ask without every harness that renders it having to provide one.
 */
export const useIsDemo = (): boolean => {
  const context = useContext(AuthContext);
  return context?.user?.demo === true;
};
