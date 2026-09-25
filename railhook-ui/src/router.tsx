import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import AppLayout from './layout/AppLayout';
import PublicLayout from './layout/PublicLayout';
import ProtectedRoute from './auth/ProtectedRoute';
import RouteErrorScreen from './components/RouteErrorScreen';
import { publicBlogEnabled } from './lib/runtimeConfig';

const LandingPage = lazy(() => import('./pages/LandingPage'));
const LoginPage = lazy(() => import('./auth/LoginPage'));
const RegisterPage = lazy(() => import('./auth/RegisterPage'));
const VerifyEmailPage = lazy(() => import('./auth/VerifyEmailPage'));
const ConfirmEmailChangePage = lazy(() => import('./auth/ConfirmEmailChangePage'));
const CancelEmailChangePage = lazy(() => import('./auth/CancelEmailChangePage'));
const ForgotPasswordPage = lazy(() => import('./auth/ForgotPasswordPage'));
const ResetPasswordPage = lazy(() => import('./auth/ResetPasswordPage'));
const AcceptInvitePage = lazy(() => import('./auth/AcceptInvitePage'));
const DeviceApprovePage = lazy(() => import('./auth/DeviceApprovePage'));
const OAuthConsentPage = lazy(() => import('./auth/OAuthConsentPage'));
const AuthCallbackPage = lazy(() => import('./auth/AuthCallbackPage'));
const PricingPage = lazy(() => import('./pages/PricingPage'));
const TesterPage = lazy(() => import('./pages/TesterPage'));
const DemoPage = lazy(() => import('./pages/DemoPage'));
const SignatureVerifierPage = lazy(() => import('./pages/SignatureVerifierPage'));
const BlogPage = lazy(() => import('./pages/BlogPage'));
const BlogPostPage = lazy(() => import('./pages/BlogPostPage'));
const PrivacyPage = lazy(() => import('./pages/LegalPage').then((m) => ({ default: m.PrivacyPage })));
const TermsPage = lazy(() => import('./pages/LegalPage').then((m) => ({ default: m.TermsPage })));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const ProjectsPage = lazy(() => import('./pages/ProjectsPage'));
const ProjectSetupPage = lazy(() => import('./pages/ProjectSetupPage'));
const EndpointsPage = lazy(() => import('./pages/EndpointsPage'));
const DeliveriesPage = lazy(() => import('./pages/DeliveriesPage'));
const EventsPage = lazy(() => import('./pages/EventsPage'));
const SubscriptionsPage = lazy(() => import('./pages/SubscriptionsPage'));
const MembersPage = lazy(() => import('./pages/MembersPage'));
const ApiKeysPage = lazy(() => import('./pages/ApiKeysPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const OrgSettingsPage = lazy(() => import('./pages/OrgSettingsPage'));
const BillingPage = lazy(() => import('./pages/BillingPage'));
const AnalyticsPage = lazy(() => import('./pages/AnalyticsPage'));
const ReplayPage = lazy(() => import('./pages/ReplayPage'));
const DlqPage = lazy(() => import('./pages/DlqPage'));
const IncomingDlqPage = lazy(() => import('./pages/IncomingDlqPage'));
const TestEndpointsPage = lazy(() => import('./pages/TestEndpointsPage'));
const AuditLogPage = lazy(() => import('./pages/AuditLogPage'));
const PlatformAdminGate = lazy(() => import('./components/PlatformAdminGate'));
const PlatformOverviewPage = lazy(() => import('./pages/PlatformOverviewPage'));
const PlatformOrganizationsPage = lazy(() => import('./pages/PlatformOrganizationsPage'));
const PlatformOrganizationDetailPage = lazy(() => import('./pages/PlatformOrganizationDetailPage'));
const PlatformUsersPage = lazy(() => import('./pages/PlatformUsersPage'));
const IncomingSourcesPage = lazy(() => import('./pages/IncomingSourcesPage'));
const IncomingSourceDetailPage = lazy(() => import('./pages/IncomingSourceDetailPage'));
const IncomingEventsPage = lazy(() => import('./pages/IncomingEventsPage'));
const SchemasPage = lazy(() => import('./pages/SchemasPage'));
const PiiRulesPage = lazy(() => import('./pages/PiiRulesPage'));
const EventDiffPage = lazy(() => import('./pages/EventDiffPage'));
const AlertsPage = lazy(() => import('./pages/AlertsPage'));
const UsagePage = lazy(() => import('./pages/UsagePage'));
const EventDetailPage = lazy(() => import('./pages/EventDetailPage'));
const IncidentsPage = lazy(() => import('./pages/IncidentsPage'));
const RulesPage = lazy(() => import('./pages/RulesPage'));
const TransformationsPage = lazy(() => import('./pages/TransformationsPage'));
const TransformationHistoryPage = lazy(() => import('./pages/TransformationHistoryPage'));
const TransformStudioPage = lazy(() => import('./pages/TransformStudioPage'));
const ConnectionSetupPage = lazy(() => import('./pages/ConnectionSetupPage'));
const ConnectionsPage = lazy(() => import('./pages/ConnectionsPage'));
const WorkflowsPage = lazy(() => import('./pages/WorkflowsPage'));
const WorkflowBuilderPage = lazy(() => import('./pages/WorkflowBuilderPage'));
const TunnelsPage = lazy(() => import('./pages/TunnelsPage'));
const TestConsolePage = lazy(() => import('./pages/TestConsolePage'));
const SharedDebugPage = lazy(() => import('./pages/SharedDebugPage'));
const PortalPage = lazy(() => import('./pages/PortalPage'));
const ConsumersPage = lazy(() => import('./pages/ConsumersPage'));
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'));

function PageLoader() {
  return (
    <div className="flex items-center justify-center min-h-[60vh]">
      <Loader2 className="h-6 w-6 animate-spin text-primary" />
    </div>
  );
}

function S({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<PageLoader />}>{children}</Suspense>;
}

/** Covers client-side navigation; with the blog off nginx already answers 404 for /blog. */
function BlogOnly({ children }: { children: React.ReactNode }) {
  return publicBlogEnabled() ? children : <NotFoundPage />;
}

export const router = createBrowserRouter([
  /* One pathless root so every route, public ones included, shares an errorElement. */
  {
    errorElement: <RouteErrorScreen />,
    children: [
      {
        element: <PublicLayout />,
        children: [
          {
            path: '/',
            element: <S><LandingPage /></S>,
          },
          {
            path: '/pricing',
            element: <S><PricingPage /></S>,
          },
          {
            path: '/tester',
            element: <S><TesterPage /></S>,
          },
          {
            /* Not in the sitemap: it opens a session and moves on, there is nothing to index. */
            path: '/demo',
            element: <S><DemoPage /></S>,
          },
          {
            path: '/tools/webhook-signature',
            element: <S><SignatureVerifierPage /></S>,
          },
          {
            path: '/blog',
            element: <S><BlogOnly><BlogPage /></BlogOnly></S>,
          },
          /* Slugs are the directories under src/content/blog/, enumerated by scripts/public-routes.mjs. */
          {
            path: '/blog/:slug',
            element: <S><BlogOnly><BlogPostPage /></BlogOnly></S>,
          },
          {
            path: '/privacy',
            element: <S><PrivacyPage /></S>,
          },
          {
            path: '/terms',
            element: <S><TermsPage /></S>,
          },
        ],
      },
      {
        path: '/login',
        element: <S><LoginPage /></S>,
      },
      {
        path: '/register',
        element: <S><RegisterPage /></S>,
      },
      {
        path: '/verify-email',
        element: <S><VerifyEmailPage /></S>,
      },
      /* Public: either email-change link can be opened with no session. */
      {
        path: '/confirm-email-change',
        element: <S><ConfirmEmailChangePage /></S>,
      },
      {
        path: '/cancel-email-change',
        element: <S><CancelEmailChangePage /></S>,
      },
      {
        path: '/forgot-password',
        element: <S><ForgotPasswordPage /></S>,
      },
      {
        path: '/reset-password',
        element: <S><ResetPasswordPage /></S>,
      },
      {
        path: '/accept-invite',
        element: <S><AcceptInvitePage /></S>,
      },
      {
        path: '/device',
        element: <S><DeviceApprovePage /></S>,
      },
      /* The only route another site may frame; its credential is the token in the URL fragment. */
      {
        path: '/portal',
        element: <S><PortalPage /></S>,
      },
      {
        path: '/oauth/consent',
        element: <S><OAuthConsentPage /></S>,
      },
      {
        path: '/auth/callback',
        element: <S><AuthCallbackPage /></S>,
      },
      /* No /docs route: nginx serves the separate docs site, so a link to it is a full page load. */
      /* Roles are declared once in nav.config's requiredRoleFor, never per route: two lists drifted before. */
      {
        path: '/admin',
        element: (
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        ),
        children: [
          {
            index: true,
            element: <Navigate to="dashboard" replace />,
          },
          {
            path: 'dashboard',
            element: <S><DashboardPage /></S>,
          },
          {
            path: 'projects',
            element: <S><ProjectsPage /></S>,
          },
          {
            path: 'start/:segment',
            element: <S><ProjectSetupPage /></S>,
          },
          {
            path: 'projects/:projectId/endpoints',
            element: <S><EndpointsPage /></S>,
          },
          {
            path: 'projects/:projectId/deliveries',
            element: <S><DeliveriesPage /></S>,
          },
          {
            path: 'projects/:projectId/consumers',
            element: <S><ConsumersPage /></S>,
          },
          {
            path: 'projects/:projectId/events',
            element: <S><EventsPage /></S>,
          },
          {
            path: 'projects/:projectId/subscriptions',
            element: <S><SubscriptionsPage /></S>,
          },
          {
            path: 'projects/:projectId/api-keys',
            element: <S><ApiKeysPage /></S>,
          },
          {
            path: 'projects/:projectId/analytics',
            element: <S><AnalyticsPage /></S>,
          },
          {
            path: 'projects/:projectId/replay',
            element: <S><ReplayPage /></S>,
          },
          {
            path: 'projects/:projectId/dlq',
            element: <S><DlqPage /></S>,
          },
          {
            path: 'projects/:projectId/incoming-dlq',
            element: <S><IncomingDlqPage /></S>,
          },
          {
            path: 'projects/:projectId/test-endpoints',
            element: <S><TestEndpointsPage /></S>,
          },
          {
            path: 'projects/:projectId/incoming-sources',
            element: <S><IncomingSourcesPage /></S>,
          },
          {
            path: 'projects/:projectId/incoming-sources/:sourceId',
            element: <S><IncomingSourceDetailPage /></S>,
          },
          {
            path: 'projects/:projectId/incoming-events',
            element: <S><IncomingEventsPage /></S>,
          },
          {
            path: 'projects/:projectId/schemas',
            element: <S><SchemasPage /></S>,
          },
          {
            path: 'projects/:projectId/pii-rules',
            element: <S><PiiRulesPage /></S>,
          },
          {
            path: 'projects/:projectId/event-diff',
            element: <S><EventDiffPage /></S>,
          },
          {
            path: 'projects/:projectId/alerts',
            element: <S><AlertsPage /></S>,
          },
          {
            path: 'projects/:projectId/usage',
            element: <S><UsagePage /></S>,
          },
          {
            path: 'projects/:projectId/events/:eventId',
            element: <S><EventDetailPage /></S>,
          },
          {
            path: 'projects/:projectId/incidents',
            element: <S><IncidentsPage /></S>,
          },
          {
            path: 'projects/:projectId/rules',
            element: <S><RulesPage /></S>,
          },
          {
            path: 'projects/:projectId/transformations',
            element: <S><TransformationsPage /></S>,
          },
          {
            path: 'projects/:projectId/transformations/:transformationId/history',
            element: <S><TransformationHistoryPage /></S>,
          },
          {
            path: 'projects/:projectId/transform-studio',
            element: <S><TransformStudioPage /></S>,
          },
          {
            path: 'projects/:projectId/connection-setup',
            element: <S><ConnectionSetupPage /></S>,
          },
          {
            path: 'projects/:projectId/connections',
            element: <S><ConnectionsPage /></S>,
          },
          {
            path: 'projects/:projectId/workflows',
            element: <S><WorkflowsPage /></S>,
          },
          {
            path: 'projects/:projectId/workflows/:workflowId',
            element: <S><WorkflowBuilderPage /></S>,
          },
          {
            path: 'projects/:projectId/test-console',
            element: <S><TestConsolePage /></S>,
          },
          {
            path: 'tunnels',
            element: <S><TunnelsPage /></S>,
          },
          {
            path: 'members',
            element: <S><MembersPage /></S>,
          },
          {
            path: 'audit-log',
            element: <S><AuditLogPage /></S>,
          },
          {
            path: 'settings',
            element: <S><SettingsPage /></S>,
          },
          {
            path: 'org-settings',
            element: <S><OrgSettingsPage /></S>,
          },
          {
            path: 'billing',
            element: <S><BillingPage /></S>,
          },
          /* The API re-checks platformAdmin, with the sign-in's age, on every request. */
          {
            path: 'platform',
            element: <S><PlatformAdminGate><PlatformOverviewPage /></PlatformAdminGate></S>,
          },
          {
            path: 'platform/organizations',
            element: <S><PlatformAdminGate><PlatformOrganizationsPage /></PlatformAdminGate></S>,
          },
          {
            path: 'platform/organizations/:organizationId',
            element: <S><PlatformAdminGate><PlatformOrganizationDetailPage /></PlatformAdminGate></S>,
          },
          {
            path: 'platform/users',
            element: <S><PlatformAdminGate><PlatformUsersPage /></PlatformAdminGate></S>,
          },
          {
            path: '*',
            element: <S><NotFoundPage /></S>,
          },
        ],
      },
      {
        path: '/shared/debug/:token',
        element: <S><SharedDebugPage /></S>,
      },
      {
        path: '*',
        element: <S><NotFoundPage /></S>,
      },
    ],
  },
]);
