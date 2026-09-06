import { useTranslation } from 'react-i18next';
import { DefinitionList, DocsArticle, DocsTitle, Note, Section } from '../primitives';

/**
 * Alert rules are the in-product ones a user creates against their own
 * endpoints. They are not the PrometheusRule alerts the Helm chart ships, which
 * watch the platform rather than a customer's traffic — the distinction is the
 * first thing this page has to make, because both are called "alerts".
 */
export default function Alerts() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.alerts.title')} lede={t('docsPage.alerts.subtitle')} />

      <Section title={t('docsPage.alerts.twoKinds')} description={t('docsPage.alerts.twoKindsDesc')}>
        <Note label={t('docsPage.alerts.twoKindsLabel')}>{t('docsPage.alerts.twoKindsNote')}</Note>
      </Section>

      <Section title={t('docsPage.alerts.conditions')} description={t('docsPage.alerts.conditionsDesc')}>
        <DefinitionList
          items={[
            { term: 'FAILURE_RATE', definition: t('docsPage.alerts.typeFailureRate') },
            { term: 'CONSECUTIVE_FAILURES', definition: t('docsPage.alerts.typeConsecutive') },
            { term: 'DLQ_THRESHOLD', definition: t('docsPage.alerts.typeDlq') },
            { term: 'LATENCY_THRESHOLD', definition: t('docsPage.alerts.typeLatency') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.alerts.channels')} description={t('docsPage.alerts.channelsDesc')}>
        <DefinitionList
          items={[
            { term: 'IN_APP', definition: t('docsPage.alerts.channelInApp') },
            { term: 'EMAIL', definition: t('docsPage.alerts.channelEmail') },
            { term: 'SLACK', definition: t('docsPage.alerts.channelSlack') },
            { term: 'WEBHOOK', definition: t('docsPage.alerts.channelWebhook') },
          ]}
        />
        <Note label={t('docsPage.alerts.ssrfLabel')}>{t('docsPage.alerts.ssrfDesc')}</Note>
      </Section>

      <Section title={t('docsPage.alerts.tuning')} description={t('docsPage.alerts.tuningDesc')}>
        <DefinitionList
          items={[
            { term: t('docsPage.alerts.tuneWindow'), definition: t('docsPage.alerts.tuneWindowDesc') },
            { term: t('docsPage.alerts.tuneScope'), definition: t('docsPage.alerts.tuneScopeDesc') },
            { term: t('docsPage.alerts.tuneSeverity'), definition: t('docsPage.alerts.tuneSeverityDesc') },
            { term: t('docsPage.alerts.tuneMute'), definition: t('docsPage.alerts.tuneMuteDesc') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.alerts.incidents')} description={t('docsPage.alerts.incidentsDesc')}>
        <DefinitionList
          items={[
            { term: 'OPEN', definition: t('docsPage.alerts.incidentOpen') },
            { term: 'INVESTIGATING', definition: t('docsPage.alerts.incidentInvestigating') },
            { term: 'RESOLVED', definition: t('docsPage.alerts.incidentResolved') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.alerts.notYet')}>
        <ul className="max-w-2xl list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground">
          <li>{t('docsPage.alerts.gap1')}</li>
          <li>{t('docsPage.alerts.gap2')}</li>
        </ul>
      </Section>
    </DocsArticle>
  );
}
