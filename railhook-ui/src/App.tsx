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
  // The forced sign-out below is registered once; it reads who was signed in through this.
  const userRef = useRef(user);
  userRef.current = user;

  // Restore auth state on mount via silent refresh (cookie-based)
  useEffect(() => {
    // A live-demo tab first: its token is all there is, and refreshing from this browser's cookie
    // would put the tab in whatever real account the cookie belongs to.
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
      const wasDemo = userRef.current?.demo === true;
      if (!wasDemo) {
        rememberSignedOutUser(userRef.current?.user?.id);
      }
      clearDemoSession();
      setToken(null);
      setUser(null);
      // Several cached keys name neither a user nor an organization; whoever signs in next in
      // this tab would otherwise be shown the last person's data for as long as it stays fresh.
      queryClient.clear();
      if (wasDemo) {
        // The demo ended by itself: back to where the visitor came from, and say why.
        showWarning('demo.ended');
        router.navigate('/');
      }
    });
    return () => http.setOnLogout(null);
  }, []);

  // A demo session ends when its token does, whether or not the tab makes a request then.
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
      // The request interceptor runs after this returns, by when the token below is gone: the
      // sign-out has to carry the session's token itself, or it goes out anonymous, meets a 401,
      // and refreshes a session into memory that was meant to end.
      authApi.logout(http.getToken()).catch(() => { });
      if (user?.demo) {
        // Nothing of a demo is remembered: not who was signed in, and not in the stored session
        // a real account on this browser uses.
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
      // A real session this browser holds is left as it is, in its cookie and in localStorage.
      // The demo lives in this tab only.
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
