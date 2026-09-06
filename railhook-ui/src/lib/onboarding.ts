import type { OnboardingStatus } from '../api/dashboard.api';
import type { IncomingSourceResponse } from '../types/api.types';

/**
 * What the getting-started card knows, with no React and no i18n in the way.
 *
 * <p>Every bug this module replaces was a derivation bug wearing JSX. The old
 * checklist decided a step's state inside its own markup, so two of its rows
 * were the literal `false` — unreachable forever — and a third read the flag
 * belonging to the row above it. None of that was visible to a test.
 *
 * <p>So the rule this file exists to enforce: a step's `done` is a function of
 * its inputs and nothing else. `stepsFor` takes everything it is allowed to
 * know and returns keys, never sentences. A row wired to a constant, or to a
 * neighbour's evidence, fails in CI rather than shipping.
 */

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
  /** The seven booleans the dashboard endpoint reports. */
  status: OnboardingStatus;
  /**
   * The project's incoming sources, from the list the page already loads.
   * `verifySource` is the one step the onboarding endpoint cannot answer, and
   * the list DTO carries the evidence — so it is read here rather than guessed.
   */
  sources: IncomingSourceResponse[];
}

export const INTENT_KEY = 'railhook_intent';
export const DISMISS_KEY = 'railhook_onboarding_dismissed';

const TRACKS: readonly Track[] = ['send', 'receive', 'both'];

/**
 * Which direction to show, and whether we have to ask.
 *
 * <p>The stored intent is only ever a first-session hint. The moment the
 * organization has built anything, the backend's booleans say which direction
 * it actually works in — and that answer beats the stated one, because people
 * say "both" and build one. It is also per-organization rather than per-device,
 * so it survives a new browser by construction.
 *
 * <p>Returns `null` when the account is empty and nothing was ever answered:
 * that is the case where the card asks, inline, once.
 */
export function trackFor(status: OnboardingStatus, storedIntent: Track | null): Track | null {
  const outgoing =
    status.hasEndpoints || status.hasSubscriptions || status.hasApiKeys || status.hasEvents;
  const incoming = status.hasIncomingSources || status.hasIncomingDestinations;

  if (outgoing && incoming) return 'both';
  if (outgoing) return 'send';
  if (incoming) return 'receive';
  return storedIntent;
}

/** Whether any source both asks for verification and has the secret to do it. */
function anySourceVerifies(sources: IncomingSourceResponse[]): boolean {
  return sources.some((s) => s.verificationMode !== 'NONE' && s.hmacSecretConfigured);
}

const OUTGOING = (i: OnboardingInputs): Step[] => [
  // Both booleans, because the setup flow writes the endpoint at step 1 and the
  // subscriptions at the end: an endpoint nothing is subscribed to is a flow
  // someone abandoned, and the row should say so.
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

/**
 * The steps a track offers, in order.
 *
 * <p>Two former steps are gone rather than faked. "Send a test webhook" was
 * never a checklist row — it is the ingress URL and a curl, and it lives on the
 * source's own page. "Verify event forwarding" has no evidence at all: the
 * onboarding response carries no `hasIncomingEvents`, so until it does, the
 * incoming track ends at a destination existing, which is at least true.
 */
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

/**
 * Drop the remembered direction so the card asks again.
 *
 * <p>"You can always change later" is what the question has always claimed.
 * This is the first version in which that is true.
 */
export function forgetIntent(): void {
  try {
    localStorage.removeItem(INTENT_KEY);
  } catch { /* see writeIntent */ }
}

/**
 * Dismissal, per project.
 *
 * <p>It used to be the string `'true'` for the whole account, which meant
 * hiding the card once hid it for every project you would ever create. It is
 * now the list of project ids it was hidden for, with `'*'` standing for all —
 * the shape the notification preferences already use. The legacy `'true'` reads
 * as `['*']`, so nobody's dismissal comes back unasked.
 *
 * <p>Asking for the card back clears `'*'` outright. The old flag cannot say
 * which projects the person meant, and a per-project exception encoding to
 * guess it would be more machinery than the question deserves — a visible card
 * is the default state, and any project can be dismissed again on its own.
 */
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

/** Whether the card is hidden anywhere — the state of the settings switch. */
export function isAnyDismissed(): boolean {
  return readDismissed().length > 0;
}

/** Hide or show the card across every project at once, from Settings. */
export function setAllDismissed(dismissed: boolean): void {
  try {
    localStorage.setItem(DISMISS_KEY, JSON.stringify(dismissed ? ['*'] : []));
  } catch { /* see writeIntent */ }
}
