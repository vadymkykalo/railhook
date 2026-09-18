/**
 * The cloud's free plan, as the landing page quotes it.
 *
 * These are the seeded `free` plan row (`V036__billing_plans.sql`) — the limits
 * `QuotaEnforcementAspect` applies at runtime, and the ones
 * `SeededPlanIntegrationTest` asserts. There are no paid plans, so nothing else
 * from the plans table is mirrored here: a figure no page prints is a figure that
 * can only go stale. The landing page quotes three of these; /pricing all four.
 */
export const FREE_PLAN = {
  events: 10000,
  projects: 3,
  endpointsPerProject: 5,
  retention: 7,
} as const;

export const REPO_URL = 'https://github.com/vadymkykalo/railhook';
