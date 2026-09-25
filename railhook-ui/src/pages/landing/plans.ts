/** Mirrors the seeded free plan row that SeededPlanIntegrationTest asserts; change both together. */
export const FREE_PLAN = {
  events: 10000,
  projects: 3,
  endpointsPerProject: 5,
  retention: 7,
} as const;

export const REPO_URL = 'https://github.com/vadymkykalo/railhook';
