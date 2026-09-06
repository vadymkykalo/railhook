import type { RailAttempt, AttemptOutcome } from '../components/AttemptRail';
import type {
  DeliveryAttemptResponse,
  DeliveryResponse,
  IncomingForwardAttemptResponse,
} from '../types/api.types';

/**
 * Turning what the API returns into rungs of a ladder.
 *
 * The rail draws attempts on a log scale of the wait that preceded them, so it
 * needs a delay per attempt — which no endpoint returns. Two derivations, and
 * which one applies depends only on how much the endpoint gave us:
 *
 *  - When the attempts themselves are on hand (a detail view, which fetches
 *    `/deliveries/{id}/attempts`), the wait is measured: the gap between the
 *    moment the obligation was created and the moment each attempt ran. That is
 *    the real ladder this delivery walked, uneven retries and all.
 *  - When only `attemptCount` and `maxAttempts` are on hand (every list
 *    endpoint), the walked rungs are placed on the ladder the product ships and
 *    the rungs past `attemptCount` are marked `scheduled` — the ones still
 *    owed. A ladder that has stopped advancing (delivered, abandoned, in the
 *    DLQ) is drawn with no scheduled rungs at all, because none are owed.
 *
 * The ladder below mirrors the one `AttemptRail` pads with; it is the shape of
 * the schedule, not a claim about a specific endpoint's configured delays.
 */
const LADDER_MINUTES = [0, 1, 5, 15, 60, 360, 1440, 2880];

function ladderDelay(attemptNumber: number): number {
  return LADDER_MINUTES[Math.min(attemptNumber - 1, LADDER_MINUTES.length - 1)];
}

function minutesBetween(from: string, to: string): number {
  const ms = new Date(to).getTime() - new Date(from).getTime();
  return Number.isFinite(ms) && ms > 0 ? ms / 60_000 : 0;
}

/** An attempt resolved to a 2xx is the only outcome that ends the obligation. */
export function outcomeOf(code: number | undefined, error: string | undefined): AttemptOutcome {
  if (code != null && code >= 200 && code < 300) return 'ok';
  if (error || code != null) return 'failed';
  return 'pending';
}

/** True while the ladder can still advance — i.e. another attempt is owed. */
export function ladderIsLive(status: string): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

export interface Rail {
  attempts: RailAttempt[];
  /** Total rungs, so the rail can draw the ones not yet walked. */
  maxAttempts: number;
}

/** The measured ladder, from the attempts a detail view fetched. */
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

/** The measured ladder for one Forward — the incoming counterpart of a Delivery. */
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

/**
 * The ladder inferred from counts alone — all a list endpoint gives us.
 * The last walked rung carries the outcome the delivery is currently in; the
 * ones before it are, by definition, attempts that did not succeed.
 */
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
