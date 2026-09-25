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
import { clearDemoSession, readDemoSession, saveDemoSession } from './lib/demoSession';
import { showWarning } from './lib/toast';

const queryClient = createQueryClient();

export default function App() {
  const [user, setUser] = useState<CurrentUserResponse | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const userRef = useRef(user);
  userRef.current = user;

  useEffect(() => {
    // A demo tab's token is all there is: refreshing from the cookie would enter the cookie's real account.
    const demo = readDemoSession();
    if (demo) {
      http.setToken(demo.token);
      http.setDemo(true);
      setToken(demo.token);
      setUser(demo.user);
      setLoading(false);
      return;
    }

    const storedUser = localStorage.getItem('auth_user');

    // The portal runs framed for non-users; a refresh there could sign out the dashboard in another tab.
    if (storedUser && !window.location.pathname.startsWith('/portal')) {
      // Same serialized path as a 401, so tabs restored together don't present one cookie twice.
      http.refreshSession()
        .then((accessToken) => {
          const parsedUser = JSON.parse(storedUser);
          setToken(accessToken);
          setUser(parsedUser);
          http.setToken(accessToken);
        })
        .catch(() => {
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
      const wasDemo = userRef.current?.demo === true;
      if (!wasDemo) {
        rememberSignedOutUser(userRef.current?.user?.id);
      }
      clearDemoSession();
      setToken(null);
      setUser(null);
      // Some cached keys name no user or org; the next sign-in in this tab would see stale data.
      queryClient.clear();
      if (wasDemo) {
        showWarning('demo.ended');
        router.navigate('/');
      }
    });
    return () => http.setOnLogout(null);
  }, []);

  const demoExpiresAt = user?.demo ? readDemoSession()?.expiresAt : undefined;
  useEffect(() => {
    if (!demoExpiresAt) return;
    const timer = window.setTimeout(() => {
      http.setToken(null);
      http.setDemo(false);
      clearDemoSession();
      setToken(null);
      setUser(null);
      queryClient.clear();
      showWarning('demo.ended');
      router.navigate('/');
    }, Math.max(0, Date.parse(demoExpiresAt) - Date.now()));
    return () => window.clearTimeout(timer);
  }, [demoExpiresAt]);

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
      // The interceptor runs after the token is cleared, so logout must carry the token itself.
      authApi.logout(http.getToken()).catch(() => { });
      if (user?.demo) {
        clearDemoSession();
        http.setDemo(false);
      } else {
        rememberSignedOutUser(user?.user?.id);
        localStorage.removeItem('auth_user');
      }
      setToken(null);
      setUser(null);
      http.setToken(null);
      queryClient.clear();
    },
    updateUser: (newUser: CurrentUserResponse) => {
      setUser(newUser);
      if (newUser.demo) {
        const demo = readDemoSession();
        if (demo) saveDemoSession({ ...demo, user: newUser });
        return;
      }
      localStorage.setItem('auth_user', JSON.stringify(newUser));
    },
    startDemo: (newToken: string, newUser: CurrentUserResponse, expiresAt: string) => {
      // The demo lives in this tab only; a real session in the cookie is left alone.
      saveDemoSession({ token: newToken, expiresAt, user: newUser });
      http.setToken(newToken);
      http.setDemo(true);
      setToken(newToken);
      setUser(newUser);
      queryClient.clear();
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
