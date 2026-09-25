import { useTranslation } from 'react-i18next';
import { CheckCircle2, CircleDashed, Clock, XCircle, Ban } from 'lucide-react';
import { Badge } from './ui/badge';

export type StatusKind = 'ok' | 'retry' | 'halt' | 'idle';

const ICON = {
  ok: CheckCircle2,
  retry: Clock,
  halt: XCircle,
  idle: CircleDashed,
} as const;

/** Backend InvoiceStatus is upper case; comparing to 'paid' once painted every paid invoice grey. */
export function kindOfInvoiceStatus(status: string): StatusKind {
  switch (status) {
    case 'PAID':
      return 'ok';
    case 'PAST_DUE':
      return 'halt';
    default:
      return 'idle';
  }
}

export function kindOfDeliveryStatus(status: string): StatusKind {
  switch (status) {
    case 'SUCCESS':
    case 'DELIVERED':
    case 'FORWARDED':
      return 'ok';
    case 'FAILED':
    case 'PROCESSING':
    case 'RETRYING':
      return 'retry';
    case 'DLQ':
    case 'ABANDONED':
      return 'halt';
    // Not ok (nothing arrived), not halt (nothing went wrong).
    case 'CANCELLED':
      return 'idle';
    default:
      return 'idle';
  }
}

export default function StatusBadge({
  kind, label, icon = true,
}: {
  kind: StatusKind;
  label: string;
  icon?: boolean;
}) {
  const Icon = ICON[kind];
  return (
    <Badge variant={kind}>
      {icon && <Icon className="h-3 w-3 flex-shrink-0" aria-hidden />}
      {label}
    </Badge>
  );
}

/** autoDisabled reads halt, not idle: an owner whose endpoint was switched off for them must notice. */
export function EnabledBadge({
  enabled, autoDisabled = false,
}: {
  enabled: boolean;
  autoDisabled?: boolean;
}) {
  const { t } = useTranslation();
  if (!enabled && autoDisabled) {
    return (
      <Badge variant="halt">
        <XCircle className="h-3 w-3" aria-hidden />
        {t('endpoints.autoDisabled')}
      </Badge>
    );
  }
  return (
    <Badge variant={enabled ? 'ok' : 'idle'}>
      {enabled ? (
        <CheckCircle2 className="h-3 w-3" aria-hidden />
      ) : (
        <Ban className="h-3 w-3" aria-hidden />
      )}
      {t(enabled ? 'common.enabled' : 'common.disabled')}
    </Badge>
  );
}
