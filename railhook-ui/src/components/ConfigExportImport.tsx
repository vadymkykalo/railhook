import { useState, useRef } from 'react';
import { Download, Upload, Loader2, CheckCircle2, AlertTriangle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showWarning } from '../lib/toast';
import { endpointsApi } from '../api/endpoints.api';
import { subscriptionsApi, type SubscriptionRequest } from '../api/subscriptions.api';
import type { EndpointRequest } from '../types/api.types';
import { Button } from './ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './ui/card';

interface ConfigExportImportProps {
  projectId: string;
  projectName: string;
}

interface ExportedConfig {
  version: 1;
  exportedAt: string;
  projectName: string;
  endpoints: Array<{
    url: string;
    description?: string;
    enabled: boolean;
    rateLimitPerSecond?: number;
    allowedSourceIps?: string;
  }>;
  subscriptions: Array<{
    endpointUrl: string;
    eventType: string;
    enabled: boolean;
    orderingEnabled: boolean;
    maxAttempts: number;
    timeoutSeconds: number;
    retryDelays: string;
    payloadTemplate: string | null;
    customHeaders: string | null;
  }>;
}

export default function ConfigExportImport({ projectId, projectName }: ConfigExportImportProps) {
  const { t } = useTranslation();
  const [exporting, setExporting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<{ endpoints: number; subscriptions: number; errors: string[] } | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleExport = async () => {
    setExporting(true);
    try {
      const [endpoints, subscriptions] = await Promise.all([
        endpointsApi.list(projectId),
        subscriptionsApi.list(projectId),
      ]);

      const endpointMap = new Map(endpoints.map(e => [e.id, e]));

      const config: ExportedConfig = {
        version: 1,
        exportedAt: new Date().toISOString(),
        projectName,
        endpoints: endpoints.map(e => ({
          url: e.url,
          description: e.description,
          enabled: e.enabled,
          rateLimitPerSecond: e.rateLimitPerSecond,
          allowedSourceIps: e.allowedSourceIps,
        })),
        subscriptions: subscriptions.map(s => ({
          endpointUrl: endpointMap.get(s.endpointId)?.url || '',
          eventType: s.eventType,
          enabled: s.enabled,
          orderingEnabled: s.orderingEnabled,
          maxAttempts: s.maxAttempts,
          timeoutSeconds: s.timeoutSeconds,
          retryDelays: s.retryDelays,
          payloadTemplate: s.payloadTemplate,
          customHeaders: s.customHeaders,
        })),
      };

      const blob = new Blob([JSON.stringify(config, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `railhook-config-${projectName.toLowerCase().replace(/\s+/g, '-')}-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);

      showSuccess(t('configExport.toast.exported', { endpoints: endpoints.length, subscriptions: subscriptions.length }));
    } catch (err: any) {
      showApiError(err, 'configExport.toast.exportFailed');
    } finally {
      setExporting(false);
    }
  };

  const handleImport = async (file: File) => {
    setImporting(true);
    setImportResult(null);
    const errors: string[] = [];
    let endpointsCreated = 0;
    let subscriptionsCreated = 0;

    try {
      const text = await file.text();
      const config: ExportedConfig = JSON.parse(text);

      if (config.version !== 1) {
        showWarning(t('configExport.toast.unsupportedVersion'));
        setImporting(false);
        return;
      }

      // Fetch existing endpoints to avoid duplicates and resolve subscription references
      const existingEndpoints = await endpointsApi.list(projectId);
      const existingUrls = new Set(existingEndpoints.map(e => e.url));
      const urlToEndpointId = new Map(existingEndpoints.map(e => [e.url, e.id]));

      // Import endpoints
      for (const ep of config.endpoints) {
        if (existingUrls.has(ep.url)) {
          errors.push(t('configExport.toast.endpointSkipped', { url: ep.url }));
          continue;
        }
        try {
          const req: EndpointRequest = {
            url: ep.url,
            description: ep.description,
            enabled: ep.enabled,
            rateLimitPerSecond: ep.rateLimitPerSecond,
            allowedSourceIps: ep.allowedSourceIps,
          };
          const created = await endpointsApi.create(projectId, req);
          urlToEndpointId.set(created.url, created.id);
          endpointsCreated++;
        } catch (err: any) {
          errors.push(t('configExport.toast.endpointFailed', {
            url: ep.url,
            reason: err.response?.data?.message || err.message,
          }));
        }
      }

      // Import subscriptions
      for (const sub of config.subscriptions) {
        const endpointId = urlToEndpointId.get(sub.endpointUrl);
        if (!endpointId) {
          errors.push(t('configExport.toast.subscriptionSkipped', { eventType: sub.eventType, url: sub.endpointUrl }));
          continue;
        }
        try {
          const req: SubscriptionRequest = {
            endpointId,
            eventType: sub.eventType,
            enabled: sub.enabled,
            orderingEnabled: sub.orderingEnabled,
            maxAttempts: sub.maxAttempts,
            timeoutSeconds: sub.timeoutSeconds,
            retryDelays: sub.retryDelays,
            payloadTemplate: sub.payloadTemplate || undefined,
            customHeaders: sub.customHeaders || undefined,
          };
          await subscriptionsApi.create(projectId, req);
          subscriptionsCreated++;
        } catch (err: any) {
          errors.push(t('configExport.toast.subscriptionFailed', {
            eventType: sub.eventType,
            url: sub.endpointUrl,
            reason: err.response?.data?.message || err.message,
          }));
        }
      }

      setImportResult({ endpoints: endpointsCreated, subscriptions: subscriptionsCreated, errors });

      if (errors.length === 0) {
        showSuccess(t('configExport.toast.imported', { endpoints: endpointsCreated, subscriptions: subscriptionsCreated }));
      } else {
        showWarning(t('configExport.toast.importedWithErrors', { errors: errors.length }));
      }
    } catch (err: any) {
      showApiError(err, 'configExport.toast.importFailed');
    } finally {
      setImporting(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <Download className="h-5 w-5 text-primary" />
          <CardTitle>{t('configExport.title')}</CardTitle>
        </div>
        <CardDescription>{t('configExport.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        <div className="space-y-4">
          <div className="flex flex-wrap gap-3">
            <Button onClick={handleExport} disabled={exporting} variant="outline">
              {exporting ? <Loader2 className="h-4 w-4 animate-spin mr-2" /> : <Download className="h-4 w-4 mr-2" />}
              {exporting ? t('configExport.exporting') : t('configExport.exportBtn')}
            </Button>

            <Button
              onClick={() => fileInputRef.current?.click()}
              disabled={importing}
              variant="outline"
            >
              {importing ? <Loader2 className="h-4 w-4 animate-spin mr-2" /> : <Upload className="h-4 w-4 mr-2" />}
              {importing ? t('configExport.importing') : t('configExport.importBtn')}
            </Button>
            <input
              ref={fileInputRef}
              type="file"
              accept=".json"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) handleImport(file);
              }}
            />
          </div>

          <p className="text-xs text-muted-foreground">{t('configExport.hint')}</p>

          {importResult && (
            <div className={`rounded-lg border p-3 text-sm ${importResult.errors.length > 0 ? 'border-retry/30 bg-retry-soft' : 'border-ok/30 bg-ok-soft'}`}>
              <div className="mb-1 flex items-center gap-2">
                {importResult.errors.length > 0
                  ? <AlertTriangle className="h-4 w-4 text-retry" aria-hidden />
                  : <CheckCircle2 className="h-4 w-4 text-ok" aria-hidden />
                }
                <span className="font-medium">
                  {t('configExport.importResult', { endpoints: importResult.endpoints, subscriptions: importResult.subscriptions })}
                </span>
              </div>
              {importResult.errors.length > 0 && (
                <ul className="text-xs text-muted-foreground mt-2 space-y-1 list-disc list-inside">
                  {importResult.errors.slice(0, 10).map((err, i) => (
                    <li key={i}>{err}</li>
                  ))}
                  {importResult.errors.length > 10 && (
                    <li>{t('configExport.moreErrors', { count: importResult.errors.length - 10 })}</li>
                  )}
                </ul>
              )}
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
