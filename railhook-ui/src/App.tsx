import { useState, useEffect, useRef } from 'react';
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
import { rememberSignedOutUser } from './lib/signInDestination';
import { createQueryClient } from './lib/queryClient';

const queryClient = createQueryClient();

export default function App() {
  const [user, setUser] = useState<CurrentUserResponse | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  // The forced sign-out below is registered once; it reads who was signed in through this.
  const userRef = useRef(user);
  userRef.current = user;

  // Restore auth state on mount via silent refresh (cookie-based)
  useEffect(() => {
    const storedUser = localStorage.getItem('auth_user');
    
    // The customer portal is not a dashboard page and runs for someone who is not a Railhook
    // user, often inside another site. Restoring a dashboard session there would present this
    // browser's refresh cookie for nothing — and, where the cookie is not sent to a framed page,
    // fail and sign the dashboard out in the tab next to it.
    if (storedUser && !window.location.pathname.startsWith('/portal')) {
      // Silent refresh from the httpOnly cookie, through the same serialized path a 401 takes, so
      // tabs restored together do not present one cookie twice.
      http.refreshSession()
        .then((accessToken) => {
          const parsedUser = JSON.parse(storedUser);
          setToken(accessToken);
          setUser(parsedUser);
          http.setToken(accessToken);
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
      rememberSignedOutUser(userRef.current?.user?.id);
      setToken(null);
      setUser(null);
      // Several cached keys name neither a user nor an organization; whoever signs in next in
      // this tab would otherwise be shown the last person's data for as long as it stays fresh.
      queryClient.clear();
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
      // The request interceptor runs after this returns, by when the token below is gone: the
      // sign-out has to carry the session's token itself, or it goes out anonymous, meets a 401,
      // and refreshes a session into memory that was meant to end.
      authApi.logout(http.getToken()).catch(() => { });
      rememberSignedOutUser(user?.user?.id);
      setToken(null);
      setUser(null);
      http.setToken(null);
      localStorage.removeItem('auth_user');
      queryClient.clear();
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
