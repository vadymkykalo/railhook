import { useState, useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import ThemedToaster from './components/ThemedToaster';
import { AuthContext, AuthState } from './auth/auth.store';
import { router } from './router';
import { http } from './api/http';
import { authApi } from './api/auth.api';
import { ErrorBoundary } from './components/ErrorBoundary';
import type { CurrentUserResponse } from './types/api.types';
import BootSplash from './components/BootSplash';
import { createQueryClient } from './lib/queryClient';

const queryClient = createQueryClient();

export default function App() {
  const [user, setUser] = useState<CurrentUserResponse | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // Restore auth state on mount via silent refresh (cookie-based)
  useEffect(() => {
    const storedUser = localStorage.getItem('auth_user');
    
    if (storedUser) {
      // Try silent refresh to get new access token from httpOnly cookie
      authApi.refresh()
        .then((response) => {
          const parsedUser = JSON.parse(storedUser);
          setToken(response.accessToken);
          setUser(parsedUser);
          http.setToken(response.accessToken);
        })
        .catch(() => {
          // Refresh failed, clear stored user
          localStorage.removeItem('auth_user');
        })
        .finally(() => {
          setLoading(false);
        });
    } else {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (token) {
      http.setToken(token);
    }
  }, [token]);

  useEffect(() => {
    http.setOnLogout(() => {
      setToken(null);
      setUser(null);
    });
    return () => http.setOnLogout(null);
  }, []);

  const authState: AuthState = {
    user,
    token,
    isAuthenticated: !!user && !!token,
    login: (newToken: string, newUser: CurrentUserResponse) => {
      setToken(newToken);
      setUser(newUser);
      http.setToken(newToken);
      localStorage.setItem('auth_user', JSON.stringify(newUser));
    },
    logout: () => {
      authApi.logout().catch(() => { });
      setToken(null);
      setUser(null);
      http.setToken(null);
      localStorage.removeItem('auth_user');
    },
    updateUser: (newUser: CurrentUserResponse) => {
      setUser(newUser);
      localStorage.setItem('auth_user', JSON.stringify(newUser));
    },
  };

  if (loading) return <BootSplash />;

  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthContext.Provider value={authState}>
          <RouterProvider router={router} />
          <ThemedToaster />
        </AuthContext.Provider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
