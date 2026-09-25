import { useEffect, useState } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Loader2, Lock, ShieldCheck } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import AuthLayout from './AuthLayout';
import { useAuth } from './auth.store';
import { authApi } from '../api/auth.api';
import { http } from '../api/http';
import { mcpAppsApi } from '../api/mcpApps.api';
import { projectsApi } from '../api/projects.api';
import { organizationsApi } from '../api/organizations.api';
import { queryKeys, useMcpConsentRequest } from '../api/queries';
import { Button } from '../components/ui/button';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import { leaveTo } from '../lib/leavePage';
import { showApiError } from '../lib/toast';
import { cn } from '../lib/utils';
import type { McpGrantScope } from '../types/api.types';

const SCOPES: McpGrantScope[] = ['READ_ONLY', 'READ_WRITE'];

/** The redirect comes from the API's answer, never built here. */
export default function OAuthConsentPage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const { isAuthenticated, user, login } = useAuth();
  const queryClient = useQueryClient();

  const requestId = searchParams.get('request');
  const errorCode = searchParams.get('error');
  const errorDescription = searchParams.get('error_description');

  const consent = useMcpConsentRequest(requestId, isAuthenticated && !errorCode);
  // Signed out, this page only redirects; either list would 401.
  const { data: projects = [], isLoading: projectsLoading } = useQuery({
    queryKey: queryKeys.projects.all,
    queryFn: () => projectsApi.list(),
    enabled: isAuthenticated,
  });
  const { data: organizations = [] } = useQuery({
    queryKey: queryKeys.organizations.mine,
    queryFn: () => organizationsApi.list(),
    enabled: isAuthenticated,
  });

  const [projectId, setProjectId] = useState('');
  const [scope, setScope] = useState<McpGrantScope>('READ_ONLY');
  const [switchingOrg, setSwitchingOrg] = useState(false);
  const [deciding, setDeciding] = useState<'approve' | 'deny' | null>(null);
  const [leavingTo, setLeavingTo] = useState<string | null>(null);

  const request = consent.data;

  useEffect(() => {
    if (request) {
      setScope(request.requestedScope === 'READ_WRITE' && request.canGrantWrite ? 'READ_WRITE' : 'READ_ONLY');
    }
  }, [request]);

  useEffect(() => {
    if (!projects.some((p) => p.id === projectId)) {
      setProjectId(projects[0]?.id ?? '');
    }
  }, [projects, projectId]);

  if (errorCode || !requestId) {
    return (
      <AuthLayout title={t('mcpConsent.errorTitle')} subtitle={t('mcpConsent.errorSubtitle')}>
        <div role="alert" className="border border-halt/25 bg-halt-soft p-3 text-sm text-halt">
          {errorDescription || t('mcpConsent.missingRequest')}
        </div>
      </AuthLayout>
    );
  }

  if (!isAuthenticated) {
    const here = location.pathname + location.search;
    return <Navigate to={`/login?redirect=${encodeURIComponent(here)}`} replace />;
  }

  if (leavingTo) {
    return (
      <AuthLayout title={t('mcpConsent.returningTitle')} subtitle={t('mcpConsent.returningSubtitle', { host: leavingTo })}>
        <div className="flex items-center gap-3 border border-rail bg-card p-4 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden />
          {t('mcpConsent.returning', { host: leavingTo })}
        </div>
      </AuthLayout>
    );
  }

  if (consent.isLoading) {
    return (
      <AuthLayout title={t('mcpConsent.loadingTitle')}>
        <div className="flex items-center gap-3 border border-rail bg-card p-4 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden />
          {t('common.loading')}
        </div>
      </AuthLayout>
    );
  }

  if (consent.isError || !request) {
    return (
      <AuthLayout title={t('mcpConsent.expiredTitle')} subtitle={t('mcpConsent.expiredSubtitle')}>
        <p className="border border-rail bg-card p-4 text-sm text-muted-foreground">
          {t('mcpConsent.expiredHint')}
        </p>
      </AuthLayout>
    );
  }

  const currentOrgId = user?.organization?.id ?? '';

  const handleSwitchOrganization = async (organizationId: string) => {
    if (!organizationId || organizationId === currentOrgId) return;
    setSwitchingOrg(true);
    try {
      const { accessToken } = await authApi.switchOrganization(organizationId);
      http.setToken(accessToken);
      const me = await authApi.getCurrentUser();
      login(accessToken, me);
      // Projects and write permission both belong to the organization just left.
      queryClient.clear();
    } catch (error) {
      showApiError(error, 'org.switchFailed');
    } finally {
      setSwitchingOrg(false);
    }
  };

  const decide = async (decision: 'approve' | 'deny') => {
    setDeciding(decision);
    try {
      const { redirectUrl } = decision === 'approve'
        ? await mcpAppsApi.approve(request.requestId, { projectId, scope })
        : await mcpAppsApi.deny(request.requestId);
      setLeavingTo(request.redirectHost);
      leaveTo(redirectUrl);
    } catch (error) {
      showApiError(error, decision === 'approve' ? 'mcpConsent.approveFailed' : 'mcpConsent.denyFailed');
      setDeciding(null);
    }
  };

  const busy = deciding !== null || switchingOrg;
  const noProjects = !projectsLoading && projects.length === 0;

  return (
    <AuthLayout
      title={t('mcpConsent.title', { client: request.clientName })}
      subtitle={t('mcpConsent.subtitle')}
      footer={t('mcpConsent.footer')}
    >
      <div className="space-y-5">
        <div className="flex items-center gap-3 border border-rail bg-card p-4">
          <span
            aria-hidden
            className="flex h-10 w-10 flex-shrink-0 items-center justify-center bg-primary/10 font-mono text-base font-medium text-primary"
          >
            {request.clientName.charAt(0).toUpperCase()}
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-medium text-foreground">{request.clientName}</p>
            <p className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
              <Lock className="h-3 w-3 flex-shrink-0" aria-hidden />
              <span className="truncate">
                {t('mcpConsent.returnsTo')} <span className="font-mono text-foreground">{request.redirectHost}</span>
              </span>
            </p>
          </div>
        </div>

        {organizations.length > 1 && (
          <div className="space-y-1.5">
            <Label htmlFor="consent-organization">{t('mcpConsent.organization')}</Label>
            <Select
              id="consent-organization"
              aria-label={t('mcpConsent.organization')}
              value={currentOrgId}
              onChange={(e) => handleSwitchOrganization(e.target.value)}
              disabled={busy}
            >
              {organizations.map((organization) => (
                <option key={organization.id} value={organization.id}>{organization.name}</option>
              ))}
            </Select>
          </div>
        )}

        <div className="space-y-1.5">
          <Label htmlFor="consent-project">{t('mcpConsent.project')}</Label>
          {noProjects ? (
            <p className="border border-retry/25 bg-retry-soft p-3 text-sm text-retry">
              {t('mcpConsent.noProjects')}
            </p>
          ) : (
            <Select
              id="consent-project"
              aria-label={t('mcpConsent.project')}
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              disabled={busy || projectsLoading}
            >
              {projects.map((project) => (
                <option key={project.id} value={project.id}>{project.name}</option>
              ))}
            </Select>
          )}
          <p className="text-xs text-muted-foreground">{t('mcpConsent.projectHint')}</p>
        </div>

        <div className="space-y-2">
          <span id="consent-access" className="text-sm font-medium leading-none">{t('mcpConsent.access')}</span>
          <div role="radiogroup" aria-labelledby="consent-access" className="grid gap-2.5 sm:grid-cols-2">
            {SCOPES.map((s) => {
              const locked = s === 'READ_WRITE' && !request.canGrantWrite;
              return (
                <button
                  key={s}
                  type="button"
                  role="radio"
                  aria-checked={scope === s}
                  disabled={busy || locked}
                  onClick={() => setScope(s)}
                  className={cn(
                    'border p-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60',
                    scope === s ? 'border-primary bg-secondary' : 'border-rail bg-card hover:border-primary/40'
                  )}
                >
                  <span className="flex items-center gap-2 text-sm font-medium">
                    {t(s === 'READ_ONLY' ? 'apiKeys.scopeReadOnly' : 'apiKeys.scopeReadWrite')}
                    {scope === s && <Check className="ml-auto h-4 w-4 text-primary" aria-hidden />}
                  </span>
                  <span className="mt-1 block text-xs leading-snug text-muted-foreground">
                    {t(s === 'READ_ONLY' ? 'mcpConsent.readOnlyHint' : 'mcpConsent.readWriteHint')}
                  </span>
                </button>
              );
            })}
          </div>
          {!request.canGrantWrite && (
            <p className="text-xs text-muted-foreground">{t('mcpConsent.writeNeedsRole')}</p>
          )}
        </div>

        <div className="flex gap-2.5 border border-rail bg-muted/40 p-3 text-xs leading-relaxed text-muted-foreground">
          <ShieldCheck className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary" aria-hidden />
          <span>{t('mcpConsent.trustNote', { host: request.redirectHost })}</span>
        </div>

        <div className="space-y-2">
          <Button className="h-10 w-full" onClick={() => decide('approve')} disabled={busy || !projectId}>
            {deciding === 'approve' && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
            {t('mcpConsent.approve')}
          </Button>
          <Button variant="outline" className="h-10 w-full" onClick={() => decide('deny')} disabled={busy}>
            {deciding === 'deny' && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
            {t('mcpConsent.deny')}
          </Button>
        </div>
      </div>
    </AuthLayout>
  );
}
