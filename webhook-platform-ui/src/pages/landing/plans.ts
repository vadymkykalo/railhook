/**
 * The seeded plan rows, in one place.
 *
 * Every number here is `V036__billing_plans.sql`, with the tunnel limits from
 * V042 and V058 and the yearly prices from V038 — the same values
 * `QuotaEnforcementAspect` applies at runtime, and `SeededPlanIntegrationTest`
 * fails when this file and those rows disagree. It lives outside `PricingSection` because the hero quotes
 * the free plan's allowance in a sentence and the pricing grid renders it in a
 * table: two hand-typed copies of the same figure drift the first time a plan
 * changes, and the one on the hero is the one nobody thinks to update.
 *
 * `null` is unlimited, matching the `-1` the backend stores.
 */
export interface LandingPlan {
  key: 'free' | 'starter' | 'pro' | 'enterprise';
  monthly: number | null;
  yearly: number | null;
  events: number | null;
  projects: number | null;
  endpoints: number | null;
  members: number | null;
  rate: number;
  retention: number;
  tunnels: number | null;
  /** Feature rows the plan turns on, cumulative up the ladder. */
  workflows: boolean;
  mtls: boolean;
  support: boolean;
}

export const PLANS: LandingPlan[] = [
  { key: 'free', monthly: 0, yearly: null, events: 10000, projects: 3, endpoints: 5, members: 5, rate: 10, retention: 7, tunnels: 1, workflows: false, mtls: false, support: false },
  { key: 'starter', monthly: 29, yearly: 290, events: 100000, projects: 10, endpoints: 20, members: 10, rate: 50, retention: 30, tunnels: 3, workflows: true, mtls: false, support: false },
  { key: 'pro', monthly: 99, yearly: 990, events: 1000000, projects: 50, endpoints: 100, members: 50, rate: 200, retention: 90, tunnels: 10, workflows: true, mtls: true, support: false },
  { key: 'enterprise', monthly: null, yearly: null, events: null, projects: null, endpoints: null, members: null, rate: 1000, retention: 365, tunnels: null, workflows: true, mtls: true, support: true },
];

/** The plan the hero's CTA note describes. */
export const FREE_PLAN = PLANS[0] as LandingPlan & { events: number };

/** The self-hosted build's only ceiling: requests a second. */
export const SELF_HOSTED_RATE = 10000;

/** The single route to a human about a paid plan. */
export const CONTACT_PATH = '/contact';

export const REPO_URL = 'https://github.com/vadymkykalo/railhook';
