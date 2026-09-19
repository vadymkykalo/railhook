/**
 * The docs slug contract: every page the site is meant to have, in sidebar order.
 *
 * The sidebar in `astro.config.mjs` is built from this list. A page is added by adding its
 * slug here and writing `src/content/docs/<slug>.mdx` plus `src/content/docs/uk/<slug>.mdx`.
 *
 * `index` and `api-reference` are not in a group: `index` is the docs home, reached from
 * the site title, and `api-reference` is a custom Astro page (Scalar), not a content entry.
 */

/** @typedef {{ label: string, uk: string, slugs: string[] }} SidebarGroup */

/** @type {SidebarGroup[]} */
export const SIDEBAR_GROUPS = [
  {
    label: 'Get started',
    uk: 'Початок',
    slugs: ['start/quickstart', 'start/send-first-webhook', 'start/receive-first-webhook', 'start/concepts'],
  },
  {
    label: 'Self-hosting',
    uk: 'Свій сервер',
    slugs: [
      'self-hosting/overview',
      'self-hosting/requirements',
      'self-hosting/install-docker',
      'self-hosting/domain-https',
      'self-hosting/local-development',
      'self-hosting/configuration',
      'self-hosting/upgrade-backup',
      'self-hosting/monitoring',
      'self-hosting/platform-admin',
      'self-hosting/live-demo',
      'self-hosting/troubleshooting',
      'self-hosting/kubernetes',
    ],
  },
  {
    label: 'Sending webhooks',
    uk: 'Надсилання',
    slugs: [
      'outgoing/endpoints-subscriptions',
      'outgoing/signatures',
      'outgoing/retries',
      'outgoing/ordering',
      'outgoing/replay',
      'outgoing/transformations',
      'outgoing/rules',
      'outgoing/schema-registry',
      'outgoing/workflows',
      'outgoing/endpoint-security',
      'outgoing/customer-portal',
    ],
  },
  {
    label: 'Receiving webhooks',
    uk: 'Приймання',
    slugs: ['incoming/sources', 'incoming/destinations', 'incoming/verification'],
  },
  {
    label: 'Provider guides',
    uk: 'Гайди провайдерів',
    slugs: [
      'guides/stripe-webhooks',
      'guides/github-webhooks',
      'guides/gitlab-webhooks',
      'guides/shopify-webhooks',
      'guides/slack-webhooks',
      'guides/twilio-webhooks',
    ],
  },
  {
    label: 'Tools',
    uk: 'Інструменти',
    slugs: ['tools/cli', 'tools/sdks', 'tools/mcp'],
  },
  {
    label: 'Platform',
    uk: 'Платформа',
    slugs: [
      'platform/authentication',
      'platform/organizations-rbac',
      'platform/pii-masking',
      'platform/alerts',
      'platform/errors-limits',
      'platform/observability',
    ],
  },
  {
    label: 'Compare',
    uk: 'Порівняння',
    slugs: [
      'compare/svix',
      'compare/hookdeck',
      'compare/convoy',
      'compare/hook0',
      'compare/open-source-webhook-tools',
    ],
  },
  {
    label: 'Resources',
    uk: 'Ресурси',
    slugs: [
      'resources/comparison',
      'resources/migrating',
      'resources/data-retention',
      'resources/static-egress-ip',
    ],
  },
];

/** The API reference: a custom page, so it is addressed by path rather than by content slug. */
export const API_REFERENCE = { slug: 'api-reference', label: 'API reference', uk: 'Довідник API' };

/** Every slug in the contract, the docs home and the API reference included. */
export const CONTRACT_SLUGS = ['index', ...SIDEBAR_GROUPS.flatMap((g) => g.slugs), API_REFERENCE.slug];
