import { useState } from 'react';
import { Loader2, ShieldCheck, Upload, X, AlertTriangle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { Button } from './ui/button';
import { Label } from './ui/label';
import { Textarea } from './ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog';
import {
  Alert,
  AlertDescription,
} from './ui/alert';
import { endpointsApi, MtlsConfigRequest } from '../api/endpoints.api';
import type { EndpointResponse } from '../types/api.types';

interface MtlsConfigModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectId: string;
  endpoint: EndpointResponse;
  onUpdate: (endpoint: EndpointResponse) => void;
}

export default function MtlsConfigModal({
  open,
  onOpenChange,
  projectId,
  endpoint,
  onUpdate,
}: MtlsConfigModalProps) {
  const { t } = useTranslation();
  const [clientCert, setClientCert] = useState('');
  const [clientKey, setClientKey] = useState('');
  const [caCert, setCaCert] = useState('');
  const [saving, setSaving] = useState(false);
  const [disabling, setDisabling] = useState(false);

  const handleFileUpload = (
    e: React.ChangeEvent<HTMLInputElement>,
    setter: (value: string) => void
  ) => {
    const file = e.target.files?.[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (event) => {
        setter(event.target?.result as string);
      };
      reader.readAsText(file);
    }
  };

  const handleSave = async () => {
    if (!clientCert.trim()) {
      showApiError(new Error('Client certificate is required'), 'toast.errors.validation');
      return;
    }
    if (!clientKey.trim()) {
      showApiError(new Error('Client private key is required'), 'toast.errors.validation');
      return;
    }

    try {
      setSaving(true);
      const data: MtlsConfigRequest = {
        clientCert: clientCert.trim(),
        clientKey: clientKey.trim(),
        caCert: caCert.trim() || undefined,
      };
      const updated = await endpointsApi.configureMtls(projectId, endpoint.id, data);
      onUpdate(updated);
      showSuccess(t('mtls.toast.configured'));
      onOpenChange(false);
      resetForm();
    } catch (err: any) {
      showApiError(err, 'toast.errors.server');
    } finally {
      setSaving(false);
    }
  };

  const handleDisable = async () => {
    try {
      setDisabling(true);
      const updated = await endpointsApi.disableMtls(projectId, endpoint.id);
      onUpdate(updated);
      showSuccess(t('mtls.toast.disabled'));
      onOpenChange(false);
      resetForm();
    } catch (err: any) {
      showApiError(err, 'toast.errors.server');
    } finally {
      setDisabling(false);
    }
  };

  const resetForm = () => {
    setClientCert('');
    setClientKey('');
    setCaCert('');
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <ShieldCheck className="h-5 w-5 text-primary" />
            {t('mtls.title')}
          </DialogTitle>
          <DialogDescription>
            {t('mtls.description')}
          </DialogDescription>
        </DialogHeader>

        {endpoint.mtlsEnabled && (
          <Alert className="border-ok/30 bg-ok-soft">
            <ShieldCheck className="h-4 w-4 text-ok" />
            <AlertDescription className="text-ok">
              {t('mtls.enabledBanner')}
            </AlertDescription>
          </Alert>
        )}

        <div className="space-y-4 py-4">
          <Alert className="border-retry/30 bg-retry-soft">
            <AlertTriangle className="h-4 w-4 text-retry" />
            <AlertDescription className="text-retry">
              {t('mtls.securityWarning')}
            </AlertDescription>
          </Alert>

          <div className="space-y-2">
            <Label htmlFor="clientCert">{t('mtls.clientCert')} *</Label>
            <div className="flex gap-2">
              <Textarea
                id="clientCert"
                placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
                value={clientCert}
                onChange={(e) => setClientCert(e.target.value)}
                className="min-h-[120px] font-mono text-xs"
              />
            </div>
            <div className="flex items-center gap-2">
              <input
                type="file"
                accept=".pem,.crt,.cer"
                onChange={(e) => handleFileUpload(e, setClientCert)}
                className="hidden"
                id="certUpload"
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => document.getElementById('certUpload')?.click()}
              >
                <Upload className="h-4 w-4 mr-2" />
                {t('mtls.uploadCert')}
              </Button>
              {clientCert && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setClientCert('')}
                  aria-label={t('mtls.clearCert', 'Clear certificate')}
                >
                  <X className="h-4 w-4" />
                </Button>
              )}
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="clientKey">{t('mtls.clientKey')} *</Label>
            <Textarea
              id="clientKey"
              placeholder="-----BEGIN PRIVATE KEY-----&#10;...&#10;-----END PRIVATE KEY-----"
              value={clientKey}
              onChange={(e) => setClientKey(e.target.value)}
              className="min-h-[120px] font-mono text-xs"
            />
            <div className="flex items-center gap-2">
              <input
                type="file"
                accept=".pem,.key"
                onChange={(e) => handleFileUpload(e, setClientKey)}
                className="hidden"
                id="keyUpload"
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => document.getElementById('keyUpload')?.click()}
              >
                <Upload className="h-4 w-4 mr-2" />
                {t('mtls.uploadKey')}
              </Button>
              {clientKey && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setClientKey('')}
                  aria-label={t('mtls.clearKey', 'Clear private key')}
                >
                  <X className="h-4 w-4" />
                </Button>
              )}
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="caCert">{t('mtls.caCert')}</Label>
            <Textarea
              id="caCert"
              placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
              value={caCert}
              onChange={(e) => setCaCert(e.target.value)}
              className="min-h-[100px] font-mono text-xs"
            />
            <p className="text-xs text-muted-foreground">
              {t('mtls.caCertHint')}
            </p>
            <div className="flex items-center gap-2">
              <input
                type="file"
                accept=".pem,.crt,.cer"
                onChange={(e) => handleFileUpload(e, setCaCert)}
                className="hidden"
                id="caUpload"
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => document.getElementById('caUpload')?.click()}
              >
                <Upload className="h-4 w-4 mr-2" />
                {t('mtls.uploadCa')}
              </Button>
              {caCert && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setCaCert('')}
                  aria-label={t('mtls.clearCa', 'Clear CA certificate')}
                >
                  <X className="h-4 w-4" />
                </Button>
              )}
            </div>
          </div>
        </div>

        <DialogFooter className="flex-col sm:flex-row gap-2">
          {endpoint.mtlsEnabled && (
            <Button
              variant="destructive"
              onClick={handleDisable}
              disabled={disabling || saving}
              className="w-full sm:w-auto"
            >
              {disabling && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              {t('mtls.disable')}
            </Button>
          )}
          <div className="flex-1" />
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t('common.cancel')}
          </Button>
          <Button onClick={handleSave} disabled={saving || disabling}>
            {saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            {endpoint.mtlsEnabled ? t('mtls.updateCerts') : t('mtls.enable')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
