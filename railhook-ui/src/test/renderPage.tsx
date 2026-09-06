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
  // Matches the shipped default (EMAIL_ENABLED=false), so a page under test
  // renders the "nothing was sent" wording the majority of installs see.
  emailDeliveryEnabled: false,
};

const FAKE_AUTH_STATE: AuthState = {
  user: FAKE_USER,
  token: 'fake-token',
  login: () => {},
  logout: () => {},
  updateUser: () => {},
  isAuthenticated: true,
};

/** Fresh QueryClient per test — no retries (would hang error-state tests) and no caching across tests. */
export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

interface RenderPageOptions {
  /** Route path pattern, e.g. '/projects/:projectId/deliveries' */
  path: string;
  /** Initial URL to render at, e.g. `/projects/${TEST_PROJECT_ID}/deliveries` */
  initialEntry: string;
  queryClient?: QueryClient;
  /**
   * Overrides on the fake auth state. Almost every page here is behind a login,
   * so the default is an authenticated OWNER — but the public pages render a
   * different set of calls to action for a signed-out visitor, and that is the
   * variant worth asserting on.
   */
  auth?: Partial<AuthState>;
}

/**
 * Renders a page component inside the same provider stack the real app supplies:
 * router (for useParams), an authenticated OWNER auth context (for usePermissions),
 * and a fresh, non-retrying QueryClient (for the TanStack Query hooks).
 */
export function renderPage(ui: ReactElement, { path, initialEntry, queryClient, auth }: RenderPageOptions) {
  const client = queryClient ?? createTestQueryClient();
  const authState: AuthState = { ...FAKE_AUTH_STATE, ...auth };
  return render(
    // Matches the top-level <Suspense> in main.tsx: useTranslation() can
    // suspend while its locale bundle loads (see src/i18n). setup.ts
    // preloads both bundles synchronously so this fallback shouldn't
    // normally be hit, but it keeps tests honest with production wiring.
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
