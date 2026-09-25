/** @typedef {{ label: string, uk: string, slugs: string[] }} SidebarGroup */

/** @type {SidebarGroup[]} */
export const SIDEBAR_GROUPS = [
  {
    label: 'Get started',
    uk: 'Початок роботи',
    slugs: ['start/quickstart', 'start/send-first-webhook', 'start/receive-first-webhook', 'start/concepts'],
  },
  {
    label: 'Self-hosting',
    uk: 'Власний сервер',
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
    uk: 'Надсилання вебхуків',
    slugs: [
      'outgoing/endpoints-subscriptions',
      'outgoing/signatures',
      'outgoing/retries',
      'outgoing/ordering',
      'outgoing/replay',
      'outgoing/event-diff',
      'outgoing/transformations',
      'outgoing/transform-studio',
      'outgoing/rules',
      'outgoing/schema-registry',
      'outgoing/workflows',
      'outgoing/endpoint-security',
      'outgoing/customer-portal',
    ],
  },
  {
    label: 'Receiving webhooks',
    uk: 'Приймання вебхуків',
    slugs: ['incoming/sources', 'incoming/destinations', 'incoming/verification'],
  },
  {
    label: 'Provider guides',
    uk: 'Посібники для сервісів',
    slugs: [
      'guides/stripe-webhooks',
      'guides/github-webhooks',
      'guides/gitlab-webhooks',
      'guides/shopify-webhooks',
      'guides/slack-webhooks',
      'guides/twilio-webhooks',
      'guides/square-webhooks',
      'guides/adyen-webhooks',
      'guides/sendgrid-webhooks',
      'guides/hubspot-webhooks',
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

export const API_REFERENCE = { slug: 'api-reference', label: 'API reference', uk: 'Довідник API' };

export const CONTRACT_SLUGS = ['index', ...SIDEBAR_GROUPS.flatMap((g) => g.slugs), API_REFERENCE.slug];
