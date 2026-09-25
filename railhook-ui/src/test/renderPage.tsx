import { Suspense, type ReactElement } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, type AuthState } from '../auth/auth.store';
import type { CurrentUserResponse } from '../types/api.types';

export const TEST_PROJECT_ID = 'project-1';

const FAKE_USER: CurrentUserResponse = {
  user: { id: 'user-1', email: 'owner@example.com', fullName: 'Test Owner', status: 'ACTIVE' },
  organization: { id: 'org-1', name: 'Test Org', createdAt: new Date().toISOString() },
  role: 'OWNER',
  // Matches the shipped default EMAIL_ENABLED=false.
  emailDeliveryEnabled: false,
  hasPassword: true,
  platformAdmin: false,
};

const FAKE_AUTH_STATE: AuthState = {
  user: FAKE_USER,
  token: 'fake-token',
  login: () => {},
  logout: () => {},
  updateUser: () => {},
  isAuthenticated: true,
};

/** No retries (they hang error-state tests) and no caching across tests. */
export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

interface RenderPageOptions {
  path: string;
  initialEntry: string;
  queryClient?: QueryClient;
  auth?: Partial<AuthState>;
}

export function renderPage(ui: ReactElement, { path, initialEntry, queryClient, auth }: RenderPageOptions) {
  const client = queryClient ?? createTestQueryClient();
  const authState: AuthState = { ...FAKE_AUTH_STATE, ...auth };
  return render(
    // Matches main.tsx's top-level <Suspense>: useTranslation() can suspend while a locale loads.
    <Suspense fallback={null}>
      <QueryClientProvider client={client}>
        <AuthContext.Provider value={authState}>
          <MemoryRouter initialEntries={[initialEntry]}>
            <Routes>
              <Route path={path} element={ui} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      </QueryClientProvider>
    </Suspense>
  );
}
