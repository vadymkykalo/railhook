import type { RailAttempt, AttemptOutcome } from '../components/AttemptRail';
import type {
  DeliveryAttemptResponse,
  DeliveryResponse,
  IncomingForwardAttemptResponse,
} from '../types/api.types';

/** Mirrors the ladder AttemptRail pads with: list endpoints return counts, not per-attempt delays. */
const LADDER_MINUTES = [0, 1, 5, 15, 60, 360, 1440, 2880];

function ladderDelay(attemptNumber: number): number {
  return LADDER_MINUTES[Math.min(attemptNumber - 1, LADDER_MINUTES.length - 1)];
}

function minutesBetween(from: string, to: string): number {
  const ms = new Date(to).getTime() - new Date(from).getTime();
  return Number.isFinite(ms) && ms > 0 ? ms / 60_000 : 0;
}

export function outcomeOf(code: number | undefined, error: string | undefined): AttemptOutcome {
  if (code != null && code >= 200 && code < 300) return 'ok';
  if (error || code != null) return 'failed';
  return 'pending';
}

export function ladderIsLive(status: string): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

export interface Rail {
  attempts: RailAttempt[];
  maxAttempts: number;
}

export function railFromDeliveryAttempts(
  attempts: DeliveryAttemptResponse[],
  delivery: Pick<DeliveryResponse, 'createdAt' | 'maxAttempts' | 'status'>
): Rail {
  const rungs = attempts.map((a) => ({
    number: a.attemptNumber,
    outcome: outcomeOf(a.httpStatusCode, a.errorMessage),
    delayMinutes: minutesBetween(delivery.createdAt, a.createdAt),
    code: a.httpStatusCode,
  }));
  return {
    attempts: rungs,
    maxAttempts: ladderIsLive(delivery.status) ? delivery.maxAttempts : rungs.length,
  };
}

export function railFromForwardAttempts(attempts: IncomingForwardAttemptResponse[]): Rail {
  const ordered = [...attempts].sort((a, b) => a.attemptNumber - b.attemptNumber);
  const first = ordered[0];
  const rungs = ordered.map((a) => ({
    number: a.attemptNumber,
    outcome:
      a.status === 'SUCCESS'
        ? ('ok' as const)
        : a.status === 'PENDING' || a.status === 'PROCESSING'
          ? ('pending' as const)
          : ('failed' as const),
    delayMinutes: first ? minutesBetween(first.createdAt, a.createdAt) : 0,
    code: a.responseCode,
  }));
  const live = ordered.some((a) => a.nextRetryAt || a.status === 'PENDING' || a.status === 'PROCESSING');
  return { attempts: rungs, maxAttempts: live ? rungs.length + 1 : rungs.length };
}

export function railFromCounts(
  attemptCount: number,
  maxAttempts: number,
  status: string
): Rail {
  const walked = Math.max(0, attemptCount);
  const attempts: RailAttempt[] = [];
  for (let n = 1; n <= walked; n++) {
    const last = n === walked;
    const outcome: AttemptOutcome = last
      ? status === 'SUCCESS'
        ? 'ok'
        : status === 'PROCESSING'
          ? 'pending'
          : 'failed'
      : 'failed';
    attempts.push({ number: n, outcome, delayMinutes: ladderDelay(n) });
  }
  return { attempts, maxAttempts: ladderIsLive(status) ? Math.max(maxAttempts, walked) : walked };
}
