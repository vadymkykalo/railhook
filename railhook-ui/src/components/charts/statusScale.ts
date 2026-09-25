import type { StatusKind } from '../StatusBadge';
import type { DeliveryStats } from '../../api/dashboard.api';

export function kindOfSeverity(severity: string): StatusKind {
  switch (severity) {
    case 'CRITICAL':
      return 'halt';
    case 'WARNING':
      return 'retry';
    default:
      return 'idle';
  }
}

export function kindOfIncidentStatus(status: string): StatusKind {
  switch (status) {
    case 'OPEN':
      return 'halt';
    case 'INVESTIGATING':
      return 'retry';
    case 'RESOLVED':
      return 'ok';
    default:
      return 'idle';
  }
}

export function kindOfEndpointStatus(status: string): StatusKind {
  switch (status) {
    case 'HEALTHY':
      return 'ok';
    case 'DEGRADED':
      return 'retry';
    case 'FAILING':
      return 'halt';
    default:
      return 'idle';
  }
}

export type QuotaKind = 'within' | 'approaching' | 'over';

export const APPROACHING_LIMIT_PERCENT = 80;

export function quotaKind(percentUsed: number): QuotaKind {
  if (!Number.isFinite(percentUsed)) return 'within';
  if (percentUsed >= 100) return 'over';
  if (percentUsed >= APPROACHING_LIMIT_PERCENT) return 'approaching';
  return 'within';
}

/** A project with no deliveries is idle, not ok: 100% of nothing is not health. */
export function verdictOfDeliveryStats(stats: DeliveryStats | undefined): StatusKind {
  if (!stats || stats.totalDeliveries <= 0) return 'idle';
  if (stats.successRate < 95) return 'halt';
  if (stats.successRate < 99 || stats.dlqDeliveries > 0 || stats.failedDeliveries > 0) return 'retry';
  return 'ok';
}

export const EMPTY_DELIVERY_STATS: DeliveryStats = {
  totalDeliveries: 0,
  successfulDeliveries: 0,
  failedDeliveries: 0,
  pendingDeliveries: 0,
  dlqDeliveries: 0,
  successRate: 0,
};

/** Payloads can arrive without the stats object; the dashboard used to crash on them. */
export function coerceDeliveryStats(stats: Partial<DeliveryStats> | undefined | null): DeliveryStats {
  if (!stats) return EMPTY_DELIVERY_STATS;
  return {
    totalDeliveries: stats.totalDeliveries ?? 0,
    successfulDeliveries: stats.successfulDeliveries ?? 0,
    failedDeliveries: stats.failedDeliveries ?? 0,
    pendingDeliveries: stats.pendingDeliveries ?? 0,
    dlqDeliveries: stats.dlqDeliveries ?? 0,
    successRate: stats.successRate ?? 0,
  };
}

/** Written out, not composed: Tailwind only emits classes it sees spelled in source. */
export const STATUS_TEXT: Record<StatusKind, string> = {
  ok: 'text-ok',
  retry: 'text-retry',
  halt: 'text-halt',
  idle: 'text-idle',
};

export const STATUS_FILL: Record<StatusKind, string> = {
  ok: 'bg-ok',
  retry: 'bg-retry',
  halt: 'bg-halt',
  idle: 'bg-idle',
};

export function kindOfSuccessRate(rate: number, enabled = true): StatusKind {
  if (!enabled) return 'idle';
  if (rate >= 99) return 'ok';
  if (rate >= 95) return 'retry';
  return 'halt';
}
