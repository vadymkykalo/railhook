import { createContext, useContext } from 'react';
import type { CurrentUserResponse } from '../types/api.types';

export interface AuthState {
  user: CurrentUserResponse | null;
  token: string | null;
  login: (token: string, user: CurrentUserResponse) => void;
  logout: () => void;
  updateUser: (user: CurrentUserResponse) => void;
  isAuthenticated: boolean;
}

export const AuthContext = createContext<AuthState | undefined>(undefined);

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
};
