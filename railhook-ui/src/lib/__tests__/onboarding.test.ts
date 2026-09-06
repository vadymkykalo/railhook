import { describe, it, expect, beforeEach } from 'vitest';
import {
  DISMISS_KEY,
  INTENT_KEY,
  forgetIntent,
  isAnyDismissed,
  isDismissed,
  progressOf,
  readIntent,
  setAllDismissed,
  setDismissed,
  stepsFor,
  trackFor,
  writeIntent,
  type OnboardingInputs,
  type Track,
} from '../onboarding';
import type { OnboardingStatus } from '../../api/dashboard.api';
import type { IncomingSourceResponse } from '../../types/api.types';

const NOTHING: OnboardingStatus = {
  hasEndpoints: false,
  hasSubscriptions: false,
  hasApiKeys: false,
  hasEvents: false,
  hasDeliveries: false,
  hasIncomingSources: false,
  hasIncomingDestinations: false,
};

const source = (over: Partial<IncomingSourceResponse> = {}): IncomingSourceResponse => ({
  id: 's1',
  projectId: 'p1',
  name: 'Stripe',
  slug: 'stripe',
  providerType: 'STRIPE',
  status: 'ACTIVE',
  ingressPathToken: 'tok',
  ingressUrl: 'https://example.test/ingress/tok',
  verificationMode: 'NONE',
  hmacSecretConfigured: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  ...over,
});

const inputs = (
  status: Partial<OnboardingStatus> = {},
  sources: IncomingSourceResponse[] = []
): OnboardingInputs => ({ status: { ...NOTHING, ...status }, sources });

describe('trackFor', () => {
  it('honours the stored intent while the account is still empty', () => {
    // The whole reason this module exists: the answer used to be written and
    // never read.
    expect(trackFor(NOTHING, 'receive')).toBe('receive');
    expect(trackFor(NOTHING, 'send')).toBe('send');
    expect(trackFor(NOTHING, 'both')).toBe('both');
  });

  it('asks when the account is empty and nothing was ever answered', () => {
    expect(trackFor(NOTHING, null)).toBeNull();
  });

  it('lets the data outrank the stated intent', () => {
    // People say "both" and build one. What the organization actually has is
    // the stronger answer, and it survives a new browser.
    expect(trackFor({ ...NOTHING, hasIncomingSources: true }, 'send')).toBe('receive');
    expect(trackFor({ ...NOTHING, hasEndpoints: true }, 'receive')).toBe('send');
  });

  it('reads any outgoing signal as the outgoing direction', () => {
    for (const key of ['hasEndpoints', 'hasSubscriptions', 'hasApiKeys', 'hasEvents'] as const) {
      expect(trackFor({ ...NOTHING, [key]: true }, null)).toBe('send');
    }
  });

  it('reads either incoming signal as the incoming direction', () => {
    for (const key of ['hasIncomingSources', 'hasIncomingDestinations'] as const) {
      expect(trackFor({ ...NOTHING, [key]: true }, null)).toBe('receive');
    }
  });

  it('returns both when the account has data in both directions', () => {
    expect(trackFor({ ...NOTHING, hasEndpoints: true, hasIncomingSources: true }, null)).toBe('both');
  });
});

describe('stepsFor', () => {
  it('offers four outgoing steps and three incoming ones', () => {
    expect(stepsFor('send', inputs()).map((s) => s.key))
      .toEqual(['createConnection', 'createApiKey', 'sendEvent', 'seeDelivery']);
    expect(stepsFor('receive', inputs()).map((s) => s.key))
      .toEqual(['createSource', 'verifySource', 'addDestination']);
  });

  it('offers both sets, outgoing first, for the both track', () => {
    expect(stepsFor('both', inputs()).map((s) => s.key)).toEqual([
      'createConnection', 'createApiKey', 'sendEvent', 'seeDelivery',
      'createSource', 'verifySource', 'addDestination',
    ]);
  });

  it('leaves the connection open until something is subscribed to the endpoint', () => {
    // The flow writes the endpoint at step 1, so abandoning at step 2 leaves
    // exactly this. Ticking on hasEndpoints alone would call it done.
    const done = (status: Partial<OnboardingStatus>) =>
      stepsFor('send', inputs(status)).find((s) => s.key === 'createConnection')!.done;

    expect(done({ hasEndpoints: true })).toBe(false);
    expect(done({ hasSubscriptions: true })).toBe(false);
    expect(done({ hasEndpoints: true, hasSubscriptions: true })).toBe(true);
  });

  it('ticks verifySource only when a source actually verifies', () => {
    // The bug this replaces: enableHmac read hasIncomingSources, the same flag
    // as the step before it, so creating a source ticked both.
    const done = (sources: IncomingSourceResponse[]) =>
      stepsFor('receive', inputs({ hasIncomingSources: true }, sources))
        .find((s) => s.key === 'verifySource')!.done;

    expect(done([source()])).toBe(false);
    expect(done([source({ verificationMode: 'HMAC_GENERIC', hmacSecretConfigured: false })])).toBe(false);
    expect(done([source({ verificationMode: 'NONE', hmacSecretConfigured: true })])).toBe(false);
    expect(done([source({ verificationMode: 'HMAC_GENERIC', hmacSecretConfigured: true })])).toBe(true);
    expect(done([source({ verificationMode: 'PROVIDER', hmacSecretConfigured: true })])).toBe(true);
  });

  it('ticks createSource while verifySource stays open', () => {
    const steps = stepsFor('receive', inputs({ hasIncomingSources: true }, [source()]));
    expect(steps.find((s) => s.key === 'createSource')!.done).toBe(true);
    expect(steps.find((s) => s.key === 'verifySource')!.done).toBe(false);
  });

  it('claims no step the backend cannot report', () => {
    // testIncomingCurl and verifyForwarding were literal `done: false`, which
    // is what made the incoming track impossible to finish.
    const keys = stepsFor('both', inputs()).map((s) => s.key);
    expect(keys).not.toContain('testIncomingCurl');
    expect(keys).not.toContain('verifyForwarding');
  });

  it('flips every step when, and only when, its own input flips', () => {
    // The rule, not the patch: a step wired to a constant or to a neighbour's
    // flag fails here rather than shipping.
    const cases: { key: string; on: OnboardingInputs }[] = [
      { key: 'createConnection', on: inputs({ hasEndpoints: true, hasSubscriptions: true }) },
      { key: 'createApiKey', on: inputs({ hasApiKeys: true }) },
      { key: 'sendEvent', on: inputs({ hasEvents: true }) },
      { key: 'seeDelivery', on: inputs({ hasDeliveries: true }) },
      { key: 'createSource', on: inputs({ hasIncomingSources: true }) },
      {
        key: 'verifySource',
        on: inputs({ hasIncomingSources: true }, [
          source({ verificationMode: 'HMAC_GENERIC', hmacSecretConfigured: true }),
        ]),
      },
      { key: 'addDestination', on: inputs({ hasIncomingDestinations: true }) },
    ];

    for (const { key, on } of cases) {
      const off = stepsFor('both', inputs()).find((s) => s.key === key)!;
      const lit = stepsFor('both', on).find((s) => s.key === key)!;
      expect(off.done, `${key} must start undone`).toBe(false);
      expect(lit.done, `${key} must tick on its own input`).toBe(true);
    }
  });
});

describe('progressOf', () => {
  it('counts what is done out of what is offered', () => {
    expect(progressOf(stepsFor('send', inputs()))).toEqual({ done: 0, total: 4, allDone: false });
    expect(progressOf(stepsFor('receive', inputs()))).toEqual({ done: 0, total: 3, allDone: false });
  });

  it('reaches the end on every track', () => {
    // Unreachable before this change for anyone with an incoming source.
    const everything = inputs(
      {
        hasEndpoints: true, hasSubscriptions: true, hasApiKeys: true, hasEvents: true,
        hasDeliveries: true, hasIncomingSources: true, hasIncomingDestinations: true,
      },
      [source({ verificationMode: 'PROVIDER', hmacSecretConfigured: true })]
    );

    for (const track of ['send', 'receive', 'both'] as Track[]) {
      expect(progressOf(stepsFor(track, everything)).allDone, track).toBe(true);
    }
  });
});

describe('storage', () => {
  beforeEach(() => localStorage.clear());

  it('round-trips the intent and reports nothing when unanswered', () => {
    expect(readIntent()).toBeNull();
    writeIntent('receive');
    expect(readIntent()).toBe('receive');
  });

  it('ignores a stored intent that is not one of the three', () => {
    localStorage.setItem(INTENT_KEY, 'sideways');
    expect(readIntent()).toBeNull();
  });

  it('dismisses one project without hiding the others', () => {
    setDismissed('p1', true);
    expect(isDismissed('p1')).toBe(true);
    expect(isDismissed('p2')).toBe(false);
  });

  it('brings a dismissed project back', () => {
    setDismissed('p1', true);
    setDismissed('p1', false);
    expect(isDismissed('p1')).toBe(false);
  });

  it('reads the legacy boolean flag as dismissed everywhere', () => {
    localStorage.setItem(DISMISS_KEY, 'true');
    expect(isDismissed('p1')).toBe(true);
    expect(isDismissed('anything')).toBe(true);
  });

  it('clears the blanket legacy flag when asked to show the card again', () => {
    // The old flag was one boolean for the whole account, so it cannot say
    // which projects the person meant. Asking for the card back clears it
    // outright rather than inventing a per-project exception encoding: the
    // visible card is the default state anyway, and any project can be
    // dismissed again on its own terms.
    localStorage.setItem(DISMISS_KEY, 'true');
    setDismissed('p1', false);
    expect(isDismissed('p1')).toBe(false);
    expect(isDismissed('p2')).toBe(false);
  });

  it('forgets the direction so the card asks again', () => {
    // The picker's own subtitle promises "you can always change later"; until
    // there was a way to clear this, that was not true.
    writeIntent('both');
    forgetIntent();
    expect(readIntent()).toBeNull();
  });

  it('hides and shows the card everywhere at once', () => {
    expect(isAnyDismissed()).toBe(false);
    setAllDismissed(true);
    expect(isDismissed('p1')).toBe(true);
    expect(isDismissed('p2')).toBe(true);
    expect(isAnyDismissed()).toBe(true);

    setAllDismissed(false);
    expect(isDismissed('p1')).toBe(false);
    expect(isAnyDismissed()).toBe(false);
  });

  it('reports a single dismissed project to the settings switch', () => {
    setDismissed('p1', true);
    expect(isAnyDismissed()).toBe(true);
  });

  it('survives a corrupt stored value', () => {
    localStorage.setItem(DISMISS_KEY, '{not json');
    expect(isDismissed('p1')).toBe(false);
  });
});
