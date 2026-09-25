import type { OnboardingStatus } from '../api/dashboard.api';
import type { IncomingSourceResponse } from '../types/api.types';

/** A step's `done` is a function of its inputs only, so a row wired to a constant fails in CI. */

export type Track = 'send' | 'receive' | 'both';

export type StepKey =
  | 'createConnection'
  | 'createApiKey'
  | 'sendEvent'
  | 'seeDelivery'
  | 'createSource'
  | 'verifySource'
  | 'addDestination';

export interface Step {
  key: StepKey;
  done: boolean;
}

export interface OnboardingInputs {
  status: OnboardingStatus;
  /** The onboarding endpoint cannot answer verifySource; the source list carries the evidence. */
  sources: IncomingSourceResponse[];
}

export const INTENT_KEY = 'railhook_intent';
export const DISMISS_KEY = 'railhook_onboarding_dismissed';

const TRACKS: readonly Track[] = ['send', 'receive', 'both'];

/** What the organization built beats the stored intent: people say "both" and build one. */
export function trackFor(status: OnboardingStatus, storedIntent: Track | null): Track | null {
  const outgoing =
    status.hasEndpoints || status.hasSubscriptions || status.hasApiKeys || status.hasEvents;
  const incoming = status.hasIncomingSources || status.hasIncomingDestinations;

  if (outgoing && incoming) return 'both';
  if (outgoing) return 'send';
  if (incoming) return 'receive';
  return storedIntent;
}

function anySourceVerifies(sources: IncomingSourceResponse[]): boolean {
  return sources.some((s) => s.verificationMode !== 'NONE' && s.hmacSecretConfigured);
}

const OUTGOING = (i: OnboardingInputs): Step[] => [
  // Both flags: an endpoint nothing subscribes to is an abandoned setup flow.
  { key: 'createConnection', done: i.status.hasEndpoints && i.status.hasSubscriptions },
  { key: 'createApiKey', done: i.status.hasApiKeys },
  { key: 'sendEvent', done: i.status.hasEvents },
  { key: 'seeDelivery', done: i.status.hasDeliveries },
];

const INCOMING = (i: OnboardingInputs): Step[] => [
  { key: 'createSource', done: i.status.hasIncomingSources },
  { key: 'verifySource', done: anySourceVerifies(i.sources) },
  { key: 'addDestination', done: i.status.hasIncomingDestinations },
];

/** No forwarding step: the onboarding response has no hasIncomingEvents to prove it. */
export function stepsFor(track: Track, inputs: OnboardingInputs): Step[] {
  if (track === 'send') return OUTGOING(inputs);
  if (track === 'receive') return INCOMING(inputs);
  return [...OUTGOING(inputs), ...INCOMING(inputs)];
}

export function progressOf(steps: Step[]): { done: number; total: number; allDone: boolean } {
  const done = steps.filter((s) => s.done).length;
  return { done, total: steps.length, allDone: steps.length > 0 && done === steps.length };
}

export function readIntent(): Track | null {
  try {
    const stored = localStorage.getItem(INTENT_KEY);
    return TRACKS.includes(stored as Track) ? (stored as Track) : null;
  } catch {
    return null;
  }
}

export function writeIntent(intent: Track): void {
  try {
    localStorage.setItem(INTENT_KEY, intent);
  } catch { /* a browser refusing storage is not a reason to fail the answer */ }
}

export function forgetIntent(): void {
  try {
    localStorage.removeItem(INTENT_KEY);
  } catch { /* see writeIntent */ }
}

/** Legacy 'true' reads as ['*'], so nobody's dismissal comes back unasked. */
function readDismissed(): string[] {
  try {
    const stored = localStorage.getItem(DISMISS_KEY);
    if (!stored) return [];
    if (stored === 'true') return ['*'];
    const parsed = JSON.parse(stored);
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : [];
  } catch {
    return [];
  }
}

export function isDismissed(projectId: string): boolean {
  const hidden = readDismissed();
  return hidden.includes('*') || hidden.includes(projectId);
}

export function setDismissed(projectId: string, dismissed: boolean): void {
  const hidden = readDismissed();

  const next = dismissed
    ? Array.from(new Set([...hidden, projectId]))
    : hidden.filter((id) => id !== projectId && id !== '*');

  try {
    localStorage.setItem(DISMISS_KEY, JSON.stringify(next));
  } catch { /* see writeIntent */ }
}

export function isAnyDismissed(): boolean {
  return readDismissed().length > 0;
}

export function setAllDismissed(dismissed: boolean): void {
  try {
    localStorage.setItem(DISMISS_KEY, JSON.stringify(dismissed ? ['*'] : []));
  } catch { /* see writeIntent */ }
}
