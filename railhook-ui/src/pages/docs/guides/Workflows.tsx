import { useTranslation } from 'react-i18next';
import { DefinitionList, DocsArticle, DocsTitle, Note, Section } from '../primitives';

export default function Workflows() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.workflows.title')} lede={t('docsPage.workflows.subtitle')} />

      <Section title={t('docsPage.workflows.howItWorks')} description={t('docsPage.workflows.howItWorksDesc')}>
        <Note label={t('docsPage.workflows.dagLabel')}>{t('docsPage.workflows.dagDesc')}</Note>
      </Section>

      <Section title={t('docsPage.workflows.nodeTypes')}>
        <DefinitionList
          items={[
            { term: 'TRIGGER', definition: t('docsPage.workflows.nodeTrigger') },
            { term: 'FILTER', definition: t('docsPage.workflows.nodeFilter') },
            { term: 'TRANSFORM', definition: t('docsPage.workflows.nodeTransform') },
            { term: 'HTTP', definition: t('docsPage.workflows.nodeHttp') },
            { term: 'SLACK', definition: t('docsPage.workflows.nodeSlack') },
            { term: 'CREATE_EVENT', definition: t('docsPage.workflows.nodeCreateEvent') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.workflows.triggerTypes')}>
        <DefinitionList
          items={[
            { term: 'WEBHOOK_EVENT', definition: t('docsPage.workflows.triggerWebhookEvent') },
            { term: 'MANUAL', definition: t('docsPage.workflows.triggerManual') },
            { term: 'SCHEDULE', definition: t('docsPage.workflows.triggerSchedule') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.workflows.executionModel')}>
        <DefinitionList
          items={[
            { term: t('docsPage.workflows.execOrder'), definition: t('docsPage.workflows.execOrderDesc') },
            { term: t('docsPage.workflows.execParallel'), definition: t('docsPage.workflows.execParallelDesc') },
            { term: t('docsPage.workflows.execFailure'), definition: t('docsPage.workflows.execFailureDesc') },
            { term: t('docsPage.workflows.execTracing'), definition: t('docsPage.workflows.execTracingDesc') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.workflows.security')}>
        <ul className="max-w-2xl list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground">
          <li>{t('docsPage.workflows.sec1')}</li>
          <li>{t('docsPage.workflows.sec2')}</li>
          <li>{t('docsPage.workflows.sec3')}</li>
        </ul>
      </Section>
    </DocsArticle>
  );
}
