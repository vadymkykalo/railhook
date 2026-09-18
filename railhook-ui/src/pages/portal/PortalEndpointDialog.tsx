import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, Plus, X } from 'lucide-react';
import type { PortalEndpointRequest, PortalEndpointResponse } from '../../types/api.types';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Switch } from '../../components/ui/switch';
import { Textarea } from '../../components/ui/textarea';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../../components/ui/dialog';

/** Everything, whatever the project sends now or later. */
export const ALL_EVENT_TYPES = '**';

/** The API's own rule for an event type, wildcards included. */
const EVENT_TYPE_PATTERN = /^(\*{1,2}|[a-z][a-z0-9_]*)(\.([a-z][a-z0-9_]*|\*{1,2}))*$/;

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** What the session says this project sends. */
  eventTypes: string[];
  /** Present when editing; absent when registering a new endpoint. */
  endpoint?: PortalEndpointResponse;
  saving: boolean;
  onSubmit: (data: PortalEndpointRequest) => void;
}

export default function PortalEndpointDialog({ open, onOpenChange, eventTypes, endpoint, saving, onSubmit }: Props) {
  const { t } = useTranslation();
  const [url, setUrl] = useState('');
  const [description, setDescription] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [selected, setSelected] = useState<string[]>([]);
  const [custom, setCustom] = useState('');
  const [customError, setCustomError] = useState(false);

  useEffect(() => {
    if (!open) return;
    setUrl(endpoint?.url ?? '');
    setDescription(endpoint?.description ?? '');
    setEnabled(endpoint?.enabled ?? true);
    setSelected(endpoint?.eventTypes ?? []);
    setCustom('');
    setCustomError(false);
  }, [open, endpoint]);

  const everything = selected.includes(ALL_EVENT_TYPES);
  // Offered: what the project sends, plus anything this endpoint already has that the list lacks.
  const offered = Array.from(new Set([
    ...eventTypes,
    ...selected.filter((type) => type !== ALL_EVENT_TYPES),
  ])).sort();

  const toggle = (type: string) => {
    setSelected((current) => current.includes(type)
      ? current.filter((t) => t !== type)
      : [...current.filter((t) => t !== ALL_EVENT_TYPES), type]);
  };

  const toggleEverything = (on: boolean) => setSelected(on ? [ALL_EVENT_TYPES] : []);

  const addCustom = () => {
    const type = custom.trim();
    if (!type) return;
    if (!EVENT_TYPE_PATTERN.test(type)) {
      setCustomError(true);
      return;
    }
    setSelected((current) => current.includes(type)
      ? current
      : [...current.filter((t) => t !== ALL_EVENT_TYPES), type]);
    setCustom('');
    setCustomError(false);
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit({ url: url.trim(), description, enabled, eventTypes: selected });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{endpoint ? t('portal.endpointDialog.editTitle') : t('portal.endpointDialog.createTitle')}</DialogTitle>
          <DialogDescription>{t('portal.endpointDialog.description')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit}>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label htmlFor="portal-endpoint-url">{t('portal.endpointDialog.url')}</Label>
              <Input
                id="portal-endpoint-url" type="url" className="font-mono text-sm" required autoFocus
                placeholder="https://example.com/webhooks"
                value={url} onChange={(e) => setUrl(e.target.value)} disabled={saving}
              />
              <p className="text-xs text-muted-foreground">{t('portal.endpointDialog.urlHint')}</p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="portal-endpoint-description">{t('portal.endpointDialog.descriptionLabel')}</Label>
              <Textarea
                id="portal-endpoint-description" rows={2}
                value={description} onChange={(e) => setDescription(e.target.value)} disabled={saving}
              />
            </div>

            {endpoint && (
              <div className="flex items-center justify-between rounded-md border border-rail p-3">
                <Label htmlFor="portal-endpoint-enabled">{t('portal.endpointDialog.enabled')}</Label>
                <Switch id="portal-endpoint-enabled" checked={enabled} onCheckedChange={setEnabled} disabled={saving} />
              </div>
            )}

            <fieldset className="space-y-2">
              <legend className="text-sm font-medium">{t('portal.endpointDialog.eventTypes')}</legend>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox" checked={everything} disabled={saving}
                  onChange={(e) => toggleEverything(e.target.checked)}
                />
                {t('portal.eventTypes.all')}
              </label>
              {!everything && (
                <div className="max-h-48 space-y-1 overflow-auto rounded-md border border-rail p-2">
                  {offered.length === 0 && (
                    <p className="text-xs text-muted-foreground">{t('portal.endpointDialog.noKnownTypes')}</p>
                  )}
                  {offered.map((type) => (
                    <label key={type} className="flex items-center gap-2 font-mono text-[13px]">
                      <input
                        type="checkbox" checked={selected.includes(type)} disabled={saving}
                        onChange={() => toggle(type)}
                      />
                      {type}
                    </label>
                  ))}
                </div>
              )}
              {!everything && (
                <div className="space-y-1">
                  <div className="flex gap-2">
                    <Input
                      aria-label={t('portal.endpointDialog.customType')}
                      placeholder={t('portal.endpointDialog.customTypePlaceholder')}
                      className="font-mono text-sm"
                      value={custom} disabled={saving}
                      onChange={(e) => { setCustom(e.target.value); setCustomError(false); }}
                      onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addCustom(); } }}
                    />
                    <Button type="button" variant="outline" onClick={addCustom} disabled={saving || !custom.trim()}>
                      <Plus className="h-4 w-4" /> {t('portal.endpointDialog.addType')}
                    </Button>
                  </div>
                  {customError && <p className="text-xs text-halt">{t('portal.endpointDialog.invalidType')}</p>}
                </div>
              )}
              {!everything && selected.length > 0 && (
                <div className="flex flex-wrap gap-1">
                  {selected.map((type) => (
                    <span
                      key={type}
                      className="inline-flex items-center gap-1 rounded border border-rail px-1.5 py-0.5 font-mono text-[11px]"
                    >
                      {type}
                      <button
                        type="button" onClick={() => toggle(type)} disabled={saving}
                        aria-label={t('portal.endpointDialog.removeType', { type })}
                      >
                        <X className="h-3 w-3" />
                      </button>
                    </span>
                  ))}
                </div>
              )}
              {selected.length === 0 && (
                <p className="text-xs text-muted-foreground">{t('portal.endpointDialog.noneSelected')}</p>
              )}
            </fieldset>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={saving || !url.trim()}>
              {saving && <Loader2 className="h-4 w-4 animate-spin" />}
              {endpoint ? t('common.save') : t('portal.endpointDialog.create')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
