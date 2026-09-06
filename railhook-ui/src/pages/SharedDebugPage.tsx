import { useState, useEffect, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import { Shield, Clock, Eye } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { formatDateTime } from '../lib/date';
import { debugLinksApi, SharedDebugLinkPublicResponse } from '../api/debugLinks.api';
import { ErrorState } from '../components/EmptyState';
import PageSkeleton from '../components/PageSkeleton';
import { formatJson } from '../lib/json';

/**
 * A public page: whoever opens it has a link and nothing else, so it stands on
 * its own outside the admin shell. The facts the product produced — event type,
 * payload — are set in mono; the words around them are not.
 */
function Fact({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="rounded-lg border border-rail bg-card p-3.5">
      <p className="mono-label mb-1.5">{label}</p>
      <p className={`truncate text-sm ${mono ? 'font-mono' : ''}`}>{value}</p>
    </div>
  );
}

export default function SharedDebugPage() {
  const { t } = useTranslation();
  const { token } = useParams<{ token: string }>();
  const [data, setData] = useState<SharedDebugLinkPublicResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    if (!token) return;
    try {
      setLoading(true);
      const response = await debugLinksApi.viewPublic(token);
      setData(response);
    } catch (err: any) {
      const status = err?.response?.status;
      if (status === 404) {
        setError(t('sharedDebug.expired'));
      } else {
        setError(t('sharedDebug.error'));
      }
    } finally {
      setLoading(false);
    }
  }, [token, t]);

  useEffect(() => {
    if (token) loadData();
  }, [token, loadData]);


  if (loading) {
    return (
      <div className="min-h-screen bg-background">
        <PageSkeleton maxWidth="max-w-4xl" />
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background p-6">
        <div className="w-full max-w-md">
          <ErrorState
            title={t('sharedDebug.unavailable')}
            description={error ?? t('sharedDebug.error')}
            onRetry={loadData}
          />
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background">
      <div className="mx-auto max-w-4xl p-4 lg:p-6">
        <div className="flex items-start gap-3 pb-5">
          <div className="mt-0.5 flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-md border border-rail bg-card">
            <Shield className="h-4 w-4 text-primary" aria-hidden />
          </div>
          <div className="min-w-0">
            <h1 className="text-title">{t('sharedDebug.title')}</h1>
            <p className="mt-1 text-sm text-muted-foreground">{t('sharedDebug.subtitle')}</p>
          </div>
        </div>

        <div className="mb-4 grid gap-3 sm:grid-cols-3">
          <Fact label={t('sharedDebug.project')} value={data.projectName} />
          <Fact label={t('sharedDebug.eventType')} value={data.eventType} mono />
          <Fact label={t('sharedDebug.eventTime')} value={formatDateTime(data.eventCreatedAt)} mono />
        </div>

        <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <Clock className="h-3.5 w-3.5" aria-hidden />
            {t('sharedDebug.expiresAt', { date: formatDateTime(data.linkExpiresAt) })}
          </span>
          <span className="flex items-center gap-1.5">
            <Eye className="h-3.5 w-3.5" aria-hidden />
            {t('sharedDebug.piiMasked')}
          </span>
        </div>

        <section className="overflow-hidden rounded-lg border border-rail bg-card">
          <div className="flex items-center gap-2 border-b border-rail px-4 py-2.5">
            <Shield className="h-3.5 w-3.5 text-primary" aria-hidden />
            <h2 className="mono-label">{t('sharedDebug.sanitizedPayload')}</h2>
          </div>
          <pre className="max-h-[600px] overflow-auto whitespace-pre-wrap p-4 font-mono text-xs leading-relaxed">
            {formatJson(data.sanitizedPayload)}
          </pre>
        </section>

        <p className="mt-6 text-center text-xs text-muted-foreground">
          {t('sharedDebug.footer')}
        </p>
      </div>
    </div>
  );
}
