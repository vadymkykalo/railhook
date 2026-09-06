import { useTranslation } from 'react-i18next';
import { CheckCircle2, CircleDashed, Clock, XCircle, Ban } from 'lucide-react';
import { Badge } from './ui/badge';

/**
 * The single place a domain status becomes a colour.
 *
 * Every status in this product resolves to one of four meanings, and the tokens
 * are named for the meanings rather than the colours: delivered is `ok`, an
 * attempt still owed is `retry`, an obligation abandoned is `halt`, and nothing
 * tried yet is `idle`. Pages map their own vocabulary onto these four and never
 * pick a colour themselves.
 */

export type StatusKind = 'ok' | 'retry' | 'halt' | 'idle';

const ICON = {
  ok: CheckCircle2,
  retry: Clock,
  halt: XCircle,
  idle: CircleDashed,
} as const;

/** Delivery and Forward share the attempt lifecycle, so they share this mapping. */
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

/** A disabled/enabled pill, which is a configuration state rather than a status. */
export function EnabledBadge({ enabled }: { enabled: boolean }) {
  const { t } = useTranslation();
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
