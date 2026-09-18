import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Clock, Link2Off, ShieldAlert, Webhook, Send } from 'lucide-react';
import { onPortalUnauthorized, setPortalToken } from '../api/portal.api';
import { usePortalSession } from '../api/queries';
import { applyTheme } from '../lib/theme';
import { cn } from '../lib/utils';
import { ErrorState } from '../components/EmptyState';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import { brandVariables, embeddingAllowed, readPortalParams, type PortalParams } from './portal/portalParams';
import PortalEndpoints from './portal/PortalEndpoints';
import PortalDeliveries from './portal/PortalDeliveries';

/**
 * The customer portal: what a Railhook customer embeds in their own product so that their user —
 * a Consumer — can register Endpoints, choose what they receive, and see why a Delivery failed.
 *
 * It stands outside the dashboard entirely. There is no Railhook user here and no dashboard
 * session: the one credential is the portal session token the customer's backend put in the
 * URL's fragment. It is taken out of the address bar on the first render, so it does not linger
 * in history, a bookmark or a screenshot, and held in memory only.
 */

type Tab = 'endpoints' | 'deliveries';

/** Read once per page load: the fragment is gone after the first render. */
function useInitialParams(): PortalParams {
  const [params] = useState(() => {
    const read = readPortalParams(window.location);
    if (read.token) setPortalToken(read.token);
    return read;
  });
  useEffect(() => {
    if (window.location.hash) {
      window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search);
    }
  }, []);
  return params;
}

function isFramed(): boolean {
  try {
    return window.self !== window.top;
  } catch {
    // A cross-origin parent can make even the comparison throw; that is being framed.
    return true;
  }
}

function useBranding(params: PortalParams) {
  const { i18n } = useTranslation();
  useEffect(() => {
    if (params.theme) applyTheme(params.theme);
  }, [params.theme]);
  useEffect(() => {
    if (params.lang && i18n.language !== params.lang) i18n.changeLanguage(params.lang);
  }, [params.lang, i18n]);
  // On the document rather than the page's wrapper: dialogs and sheets render in a portal at the
  // end of <body>, outside anything the wrapper's variables would reach.
  useEffect(() => {
    if (!params.primary) return undefined;
    const root = document.documentElement;
    const variables = brandVariables(params.primary);
    Object.entries(variables).forEach(([name, value]) => root.style.setProperty(name, value));
    return () => Object.keys(variables).forEach((name) => root.style.removeProperty(name));
  }, [params.primary]);
}

function Notice({ icon: Icon, title, description }: { icon: React.ElementType; title: string; description: string }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-6">
      <div className="w-full max-w-md rounded-lg border border-rail bg-card p-6 text-center" role="alert">
        <Icon className="mx-auto mb-3 h-6 w-6 text-muted-foreground" aria-hidden />
        <h1 className="text-base font-semibold">{title}</h1>
        <p className="mt-2 text-sm text-muted-foreground">{description}</p>
      </div>
    </div>
  );
}

export default function PortalPage() {
  const { t } = useTranslation();
  const params = useInitialParams();
  useBranding(params);
  const [expired, setExpired] = useState(false);
  const [tab, setTab] = useState<Tab>('endpoints');

  useEffect(() => {
    onPortalUnauthorized(() => setExpired(true));
    return () => onPortalUnauthorized(null);
  }, []);

  const session = usePortalSession(!!params.token && !expired);

  if (!params.token) {
    return <Notice icon={Link2Off} title={t('portal.missing.title')} description={t('portal.missing.description')} />;
  }
  const unauthorized = (session.error as { response?: { status?: number } } | null)?.response?.status === 401;
  if (expired || unauthorized) {
    return <Notice icon={Clock} title={t('portal.expired.title')} description={t('portal.expired.description')} />;
  }
  if (session.isLoading) {
    return (
      <div className="min-h-screen bg-background">
        <PageSkeleton maxWidth="max-w-5xl">
          <SkeletonRows count={4} height="h-14" />
        </PageSkeleton>
      </div>
    );
  }
  if (session.isError || !session.data) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background p-6">
        <div className="w-full max-w-md">
          <ErrorState error={session.error} fallbackKey="portal.loadFailed" onRetry={() => session.refetch()} />
        </div>
      </div>
    );
  }

  const allowed = embeddingAllowed({
    allowedOrigin: session.data.allowedOrigin,
    originParam: params.origin,
    framed: isFramed(),
    ancestorOrigins: window.location.ancestorOrigins ? Array.from(window.location.ancestorOrigins) : undefined,
  });
  if (!allowed) {
    return (
      <Notice icon={ShieldAlert} title={t('portal.wrongOrigin.title')} description={t('portal.wrongOrigin.description')} />
    );
  }

  const tabs: { id: Tab; label: string; icon: React.ElementType }[] = [
    { id: 'endpoints', label: t('portal.tabs.endpoints'), icon: Webhook },
    { id: 'deliveries', label: t('portal.tabs.deliveries'), icon: Send },
  ];

  return (
    <div className="min-h-screen bg-background">
      <div className="mx-auto max-w-5xl p-4 lg:p-6">
        <header className="flex items-center gap-3 pb-4">
          {params.logo ? (
            <img src={params.logo} alt="" className="h-8 max-w-[160px] object-contain" referrerPolicy="no-referrer" />
          ) : (
            <div className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-md border border-rail bg-card">
              <Webhook className="h-4 w-4 text-primary" aria-hidden />
            </div>
          )}
          <div className="min-w-0">
            <h1 className="text-title truncate">{t('portal.title')}</h1>
            <p className="truncate text-sm text-muted-foreground">
              {t('portal.subtitle', { consumer: session.data.consumerName, project: session.data.projectName })}
            </p>
          </div>
        </header>

        <div role="tablist" aria-label={t('portal.title')} className="mb-4 flex gap-1 border-b border-rail">
          {tabs.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={tab === id}
              onClick={() => setTab(id)}
              className={cn(
                '-mb-px flex items-center gap-2 border-b-2 px-3 py-2 text-sm transition-colors',
                tab === id
                  ? 'border-primary font-medium text-foreground'
                  : 'border-transparent text-muted-foreground hover:text-foreground'
              )}
            >
              <Icon className="h-4 w-4" aria-hidden />
              {label}
            </button>
          ))}
        </div>

        <div role="tabpanel">
          {tab === 'endpoints'
            ? <PortalEndpoints eventTypes={session.data.eventTypes} />
            : <PortalDeliveries />}
        </div>
      </div>
    </div>
  );
}
