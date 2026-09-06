import { useTranslation } from 'react-i18next';
import {
  DefinitionList,
  DocsArticle,
  DocsTitle,
  Note,
  Prose,
  Section,
  SubSection,
} from '../primitives';

/**
 * Every setting a self-hoster has to make a decision about, and nothing else.
 *
 * `.env.dist` holds around two hundred variables and is the authority; almost all of them are
 * a timeout or a batch size that is right until a load test says otherwise. What was missing
 * was the other kind — the ones where the default is a *development* choice that becomes wrong
 * the moment the instance is reachable: placeholder secrets, an unauthenticated Postgres
 * password, `APP_BASE_URL` pointing at localhost in the emails it sends.
 *
 * The quickstart curls `localhost:8082` and said nothing about where 8082 comes from, which is
 * how this page started. Defaults are quoted so the page is readable without a second window;
 * when they and `.env.dist` disagree, `.env.dist` is right and this is a bug.
 */

/** Each row: the variable, its default, and what it decides. */
interface Setting {
  name: string;
  fallback?: string;
  key: string;
}

const REQUIRED: Setting[] = [
  { name: 'APP_ENV', fallback: 'development', key: 'appEnv' },
  { name: 'JWT_SECRET', key: 'jwtSecret' },
  { name: 'WEBHOOK_ENCRYPTION_KEY', key: 'encKey' },
  { name: 'WEBHOOK_ENCRYPTION_SALT', key: 'encSalt' },
  { name: 'POSTGRES_PASSWORD', key: 'pgPassword' },
  { name: 'REDIS_PASSWORD', key: 'redisPassword' },
  { name: 'PLATFORM_ADMIN_TOKEN', fallback: 'empty', key: 'adminToken' },
];

// One published port, so one entry. API_PORT, UI_PORT and MANAGEMENT_PORT used
// to be here; they are gone because the API and the actuator are no longer
// bound to the host at all — nginx reaches them over the Docker network.
const ADDRESSES: Setting[] = [
  { name: 'RAILHOOK_PORT', fallback: '80', key: 'railhookPort' },
  { name: 'RAILHOOK_BIND', fallback: '0.0.0.0', key: 'railhookBind' },
  { name: 'RAILHOOK_DOMAIN', fallback: 'empty', key: 'railhookDomain' },
  { name: 'APP_BASE_URL', fallback: 'http://localhost', key: 'appBaseUrl' },
  { name: 'VITE_API_URL', fallback: 'empty', key: 'viteApiUrl' },
  { name: 'CORS_ALLOWED_ORIGINS', fallback: 'http://localhost', key: 'cors' },
  { name: 'WEBHOOK_INGRESS_BASE_URL', fallback: 'http://localhost', key: 'ingressBase' },
  { name: 'TUNNEL_INGRESS_BASE_URL', fallback: 'http://localhost', key: 'tunnelBase' },
];

const STORES: Setting[] = [
  { name: 'DB_MODE', fallback: 'embedded', key: 'dbMode' },
  { name: 'DB_HOST · DB_PORT · DB_NAME · DB_USER · DB_PASSWORD', key: 'dbExternal' },
  { name: 'DB_SSL_MODE', fallback: 'disable', key: 'dbSsl' },
  { name: 'REDIS_HOST · REDIS_PORT', key: 'redis' },
  { name: 'KAFKA_BOOTSTRAP_SERVERS', key: 'kafka' },
  { name: 'KAFKA_NUM_PARTITIONS', fallback: '12', key: 'partitions' },
  { name: 'KAFKA_DELIVERY_CONCURRENCY', fallback: '6', key: 'concurrency' },
];

const DELIVERY: Setting[] = [
  { name: 'WEBHOOK_MAX_PAYLOAD_SIZE_BYTES', fallback: '262144', key: 'payloadSize' },
  { name: 'WEBHOOK_MAX_FANOUT_PER_EVENT', fallback: '100', key: 'fanout' },
  { name: 'WEBHOOK_PROJECT_RATE_LIMIT_PER_SECOND', fallback: '50', key: 'projectRate' },
  { name: 'WEBHOOK_MAX_CONCURRENT_PER_ENDPOINT', fallback: '5', key: 'concurrentPerEndpoint' },
  { name: 'WEBHOOK_ALLOW_PRIVATE_IPS', fallback: 'false', key: 'privateIps' },
  { name: 'WEBHOOK_ALLOWED_HOSTS', fallback: 'empty', key: 'allowedHosts' },
  { name: 'WEBHOOK_ENDPOINT_VERIFICATION_REQUIRED', fallback: 'false', key: 'verificationRequired' },
  { name: 'ORDERING_GAP_TIMEOUT_SECONDS', fallback: '60', key: 'gapTimeout' },
];

const HOUSEKEEPING: Setting[] = [
  { name: 'DATA_RETENTION_ATTEMPTS_DAYS', fallback: '90', key: 'attemptsDays' },
  { name: 'DATA_RETENTION_INCOMING_EVENTS_DAYS', fallback: '30', key: 'incomingDays' },
  { name: 'EMAIL_ENABLED', fallback: 'false', key: 'emailEnabled' },
  { name: 'EMAIL_FROM · SMTP_HOST · SMTP_PORT · SMTP_USERNAME · SMTP_PASSWORD', key: 'smtp' },
  { name: 'BILLING_ENABLED', fallback: 'false', key: 'billingEnabled' },
  { name: 'ENTITLEMENT_DEFAULT_RATE_LIMIT', fallback: '100', key: 'entitlementRate' },
  { name: 'ENTITLEMENT_DEFAULT_MAX_FANOUT', fallback: '50', key: 'entitlementFanout' },
  { name: 'LOG_LEVEL', fallback: 'INFO', key: 'logLevel' },
  { name: 'METRICS_ENABLED', fallback: 'true', key: 'metrics' },
  { name: 'SWAGGER_ENABLED', fallback: 'false', key: 'swagger' },
];

export default function Configuration() {
  const { t } = useTranslation();

  const rows = (settings: Setting[], group: string) =>
    settings.map((setting) => ({
      term: setting.name,
      definition: setting.fallback
        ? `${t(`docsPage.configuration.${group}.${setting.key}`)} ${t('docsPage.configuration.defaultIs', { value: setting.fallback })}`
        : t(`docsPage.configuration.${group}.${setting.key}`),
    }));

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.configuration.title')} lede={t('docsPage.configuration.subtitle')} />

      <Section title={t('docsPage.configuration.howTitle')} description={t('docsPage.configuration.howDesc')}>
        <Note label={t('docsPage.configuration.authorityLabel')}>
          {t('docsPage.configuration.authorityDesc')}
        </Note>
      </Section>

      <Section
        title={t('docsPage.configuration.requiredTitle')}
        description={t('docsPage.configuration.requiredDesc')}
      >
        <DefinitionList items={rows(REQUIRED, 'required')} />
        <Note label={t('docsPage.configuration.startupLabel')}>
          {t('docsPage.configuration.startupDesc')}
        </Note>
      </Section>

      <Section
        title={t('docsPage.configuration.addressesTitle')}
        description={t('docsPage.configuration.addressesDesc')}
      >
        <DefinitionList items={rows(ADDRESSES, 'addresses')} />
        <SubSection title={t('docsPage.configuration.publicTitle')}>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
            <Prose>{t('docsPage.configuration.publicDesc')}</Prose>
          </p>
        </SubSection>
      </Section>

      <Section title={t('docsPage.configuration.storesTitle')} description={t('docsPage.configuration.storesDesc')}>
        <DefinitionList items={rows(STORES, 'stores')} />
      </Section>

      <Section
        title={t('docsPage.configuration.deliveryTitle')}
        description={t('docsPage.configuration.deliveryDesc')}
      >
        <DefinitionList items={rows(DELIVERY, 'delivery')} />
      </Section>

      <Section
        title={t('docsPage.configuration.housekeepingTitle')}
        description={t('docsPage.configuration.housekeepingDesc')}
      >
        <DefinitionList items={rows(HOUSEKEEPING, 'housekeeping')} />
      </Section>
    </DocsArticle>
  );
}
