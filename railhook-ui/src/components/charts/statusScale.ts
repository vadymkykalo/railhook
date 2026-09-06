import type { StatusKind } from '../StatusBadge';
import type { DeliveryStats } from '../../api/dashboard.api';

/**
 * The status scale, shared by the charts and by the surfaces that read state.
 *
 * `StatusBadge` owns the four meanings; this file owns the mappings from each
 * vocabulary the API speaks onto them, so two pages can never disagree about
 * what CRITICAL or FAILING is coloured. Anything that is not one of these four
 * meanings is not a status and does not get a status hue.
 */

/** Alert and incident severity. INFO is a notice, not a problem. */
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

/** Where an incident stands. Open is unowned; investigating is owed work. */
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

/** What analytics says about one endpoint's recent deliveries. */
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

/**
 * Quota against a limit.
 *
 * Deliberately three-valued and not four: having quota left is not a status,
 * it is the absence of one, so it stays in the brand hue and the meter says
 * nothing. The scale only speaks when a human has to act.
 */
export type QuotaKind = 'within' | 'approaching' | 'over';

export const APPROACHING_LIMIT_PERCENT = 80;

export function quotaKind(percentUsed: number): QuotaKind {
  if (!Number.isFinite(percentUsed)) return 'within';
  if (percentUsed >= 100) return 'over';
  if (percentUsed >= APPROACHING_LIMIT_PERCENT) return 'approaching';
  return 'within';
}

/**
 * The one-word answer the dashboard leads with: is this project's traffic
 * healthy right now.
 *
 * A project that has never delivered anything is `idle`, not `ok` — a hundred
 * percent of nothing is not health, and saying so would hide the fact that the
 * first event has not landed yet.
 */
export function verdictOfDeliveryStats(stats: DeliveryStats | undefined): StatusKind {
  if (!stats || stats.totalDeliveries <= 0) return 'idle';
  if (stats.successRate < 95) return 'halt';
  if (stats.successRate < 99 || stats.dlqDeliveries > 0 || stats.failedDeliveries > 0) return 'retry';
  return 'ok';
}

/** `DeliveryStats` with every field defaulted — the shape a brand-new project has. */
export const EMPTY_DELIVERY_STATS: DeliveryStats = {
  totalDeliveries: 0,
  successfulDeliveries: 0,
  failedDeliveries: 0,
  pendingDeliveries: 0,
  dlqDeliveries: 0,
  successRate: 0,
};

/**
 * Normalises whatever the stats endpoint actually returned.
 *
 * The dashboard used to read `stats.deliveryStats.totalDeliveries` straight
 * off the response and crashed on a project whose payload came back without
 * the object at all. A dashboard is the first screen a new account sees; it
 * has to render before the data does.
 */
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

/**
 * Static class names per status meaning.
 *
 * Written out rather than composed (`text-${kind}`) because Tailwind scans
 * source text: a class it cannot see spelled out is a class it does not emit,
 * and the mark silently loses its colour in the production build.
 */
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

/** How one endpoint's recent success rate reads as a status. */
export function kindOfSuccessRate(rate: number, enabled = true): StatusKind {
  if (!enabled) return 'idle';
  if (rate >= 99) return 'ok';
  if (rate >= 95) return 'retry';
  return 'halt';
}
