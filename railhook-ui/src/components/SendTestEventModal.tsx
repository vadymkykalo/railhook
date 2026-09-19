import { useState, useEffect, useMemo } from 'react';
import { Loader2, Send, AlertTriangle, CheckCircle2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showWarning } from '../lib/toast';
import { eventsApi } from '../api/events.api';
import { subscriptionsApi } from '../api/subscriptions.api';
import type { SubscriptionResponse } from '../api/subscriptions.api';
import { Button } from './ui/button';
import { Input } from './ui/input';
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

function eventTypeMatchesSubscription(eventType: string, pattern: string): boolean {
  if (pattern === '**') return true;
  const eventParts = eventType.split('.');
  const patternParts = pattern.split('.');
  let ei = 0, pi = 0;
  while (ei < eventParts.length && pi < patternParts.length) {
    if (patternParts[pi] === '**') return true;
    if (patternParts[pi] === '*' || patternParts[pi] === eventParts[ei]) {
      ei++; pi++;
    } else {
      return false;
    }
  }
  return ei === eventParts.length && pi === patternParts.length;
}

interface SendTestEventModalProps {
  projectId: string;
  open: boolean;
  onClose: () => void;
  onSuccess?: () => void;
}

export default function SendTestEventModal({
  projectId,
  open,
  onClose,
  onSuccess,
}: SendTestEventModalProps) {
  const { t } = useTranslation();
  const [eventType, setEventType] = useState('');
  const [payload, setPayload] = useState('{\n  "user_id": "123",\n  "action": "created"\n}');
  const [sending, setSending] = useState(false);
  const [jsonError, setJsonError] = useState('');
  const [subscriptions, setSubscriptions] = useState<SubscriptionResponse[]>([]);

  useEffect(() => {
    if (open && projectId) {
      subscriptionsApi.list(projectId).then(setSubscriptions).catch(() => {});
    }
  }, [open, projectId]);

  const matchingCount = useMemo(() => {
    if (!eventType.trim()) return -1;
    return subscriptions.filter(s => s.enabled && eventTypeMatchesSubscription(eventType.trim(), s.eventType)).length;
  }, [eventType, subscriptions]);

  const validateJson = (text: string): boolean => {
    try {
      JSON.parse(text);
      setJsonError('');
      return true;
    } catch (e) {
      setJsonError(t('events.sendModal.invalidJson'));
      return false;
    }
  };

  const handlePayloadChange = (value: string) => {
    setPayload(value);
    if (value.trim()) {
      validateJson(value);
    } else {
      setJsonError('');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validateJson(payload)) {
      showWarning(t('events.sendModal.fixJsonBeforeSubmit'));
      return;
    }

    setSending(true);
    try {
      const data = JSON.parse(payload);
      
      const response = await eventsApi.sendTestEvent(projectId, {
        type: eventType,
        data,
      });

      // A project on the WARN policy accepts a payload that does not match its schema. Saying
      // so here is the whole point of that policy: the person who can fix the payload is the
      // one who just typed it.
      if (response.schemaWarnings?.length) {
        showWarning(
          t('events.sendModal.schemaWarnings', {
            count: response.schemaWarnings.length,
            errors: response.schemaWarnings.join('; '),
          }),
          { duration: 12000 },
        );
      }

      const count = response.deliveriesCreated || 0;
      if (count === 0) {
        showWarning(t('events.toast.noSubscriptionMatch', { eventType }), { duration: 8000 });
      } else {
        showSuccess(
          t('events.sendModal.sentSuccess', { count }),
          { duration: 5000 }
        );
      }
      
      setEventType('');
      setPayload('{\n  "user_id": "123",\n  "action": "created"\n}');
      onClose();
      onSuccess?.();
    } catch (err: any) {
      showApiError(err, 'toast.errors.server');
    } finally {
      setSending(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t('events.sendModal.title')}</DialogTitle>
          <DialogDescription>
            {t('events.sendModal.description')}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit}>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label htmlFor="eventType">{t('events.sendModal.eventType')}</Label>
              <Input
                id="eventType"
                className="font-mono text-sm"
                placeholder="user.created"
                value={eventType}
                onChange={(e) => setEventType(e.target.value)}
                required
                disabled={sending}
                autoFocus
              />
              <p className="text-xs text-muted-foreground">
                {t('events.sendModal.eventTypeHint')}
              </p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="payload">{t('events.sendModal.payload')}</Label>
              <Textarea
                id="payload"
                value={payload}
                onChange={(e) => handlePayloadChange(e.target.value)}
                disabled={sending}
                rows={12}
                className="font-mono text-sm"
                placeholder='{\n  "key": "value"\n}'
              />
              {jsonError && (
                <p className="text-sm text-halt">{jsonError}</p>
              )}
              <p className="text-xs text-muted-foreground">
                {t('events.sendModal.payloadHint')}
              </p>
            </div>

            {eventType.trim() && matchingCount === 0 && (
              <div className="rounded-md border border-retry/30 bg-retry-soft p-3">
                <p className="flex items-start gap-2 text-sm text-retry">
                  <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
                  <span>{t('events.sendModal.noMatchWarning')}</span>
                </p>
              </div>
            )}

            {eventType.trim() && matchingCount > 0 && (
              <div className="rounded-md border border-ok/30 bg-ok-soft p-3">
                <p className="flex items-center gap-2 text-sm text-ok">
                  <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden />
                  <span>{t('events.sendModal.matchInfo', { count: matchingCount })}</span>
                </p>
              </div>
            )}

            {!eventType.trim() && (
              <div className="rounded-md border border-rail bg-secondary/50 p-3">
                <p className="text-sm text-muted-foreground">
                  <strong className="text-foreground">{t('events.sendModal.noteLabel')}</strong> {t('events.sendModal.noteBody')}
                </p>
              </div>
            )}
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={onClose}
              disabled={sending}
            >
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={sending || !!jsonError}>
              {sending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              {sending ? t('events.sendModal.sending') : (
                <>
                  <Send className="mr-2 h-4 w-4" />
                  {t('events.sendModal.send')}
                </>
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
