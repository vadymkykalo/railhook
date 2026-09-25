import { createContext, useContext } from 'react';
import type { CurrentUserResponse } from '../types/api.types';

export interface AuthState {
  user: CurrentUserResponse | null;
  token: string | null;
  login: (token: string, user: CurrentUserResponse) => void;
  logout: () => void;
  updateUser: (user: CurrentUserResponse) => void;
  isAuthenticated: boolean;
  /** Stores nothing a reload would present to refresh: the demo has an access token only. */
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

/** False outside an auth provider, so harnesses need not provide one. */
export const useIsDemo = (): boolean => {
  const context = useContext(AuthContext);
  return context?.user?.demo === true;
};
