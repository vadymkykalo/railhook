import { useTranslation } from 'react-i18next';
import { DefinitionList, DocsArticle, DocsTitle, Note, Section } from '../primitives';

/**
 * The single most important sentence on this page is that masking is a display
 * control. `PiiMaskingService.sanitizePayload` is called only from read paths —
 * DeliveryService, IncomingEventService, ProjectEventsController,
 * EventDiffService, SharedDebugLinkService. Nothing in the worker's delivery
 * path calls it. Someone who believes masking redacts storage will use it for a
 * GDPR erasure request and be wrong.
 */
export default function PiiMasking() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.piiMasking.title')} lede={t('docsPage.piiMasking.subtitle')} />

      <Section title={t('docsPage.piiMasking.whatItIs')} description={t('docsPage.piiMasking.whatItIsDesc')}>
        <Note label={t('docsPage.piiMasking.displayOnlyLabel')}>
          {t('docsPage.piiMasking.displayOnlyDesc')}
        </Note>
      </Section>

      <Section title={t('docsPage.piiMasking.where')} description={t('docsPage.piiMasking.whereDesc')}>
        <DefinitionList
          items={[
            { term: t('docsPage.piiMasking.whereDeliveries'), definition: t('docsPage.piiMasking.whereDeliveriesDesc') },
            { term: t('docsPage.piiMasking.whereIncoming'), definition: t('docsPage.piiMasking.whereIncomingDesc') },
            { term: t('docsPage.piiMasking.whereDiff'), definition: t('docsPage.piiMasking.whereDiffDesc') },
            { term: t('docsPage.piiMasking.whereShared'), definition: t('docsPage.piiMasking.whereSharedDesc') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.piiMasking.ruleTypes')} description={t('docsPage.piiMasking.ruleTypesDesc')}>
        <DefinitionList
          items={[
            { term: 'BUILTIN', definition: t('docsPage.piiMasking.typeBuiltin') },
            { term: 'CUSTOM', definition: t('docsPage.piiMasking.typeCustom') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.piiMasking.styles')}>
        <DefinitionList
          items={[
            { term: 'PARTIAL', definition: t('docsPage.piiMasking.stylePartial') },
            { term: 'FULL', definition: t('docsPage.piiMasking.styleFull') },
            { term: 'HASH', definition: t('docsPage.piiMasking.styleHash') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.piiMasking.defaults')} description={t('docsPage.piiMasking.defaultsDesc')}>
        <DefinitionList
          items={[
            { term: 'email', definition: t('docsPage.piiMasking.defaultEmail') },
            { term: 'phone', definition: t('docsPage.piiMasking.defaultPhone') },
            { term: 'card', definition: t('docsPage.piiMasking.defaultCard') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.piiMasking.notThis')}>
        <ul className="max-w-2xl list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground">
          <li>{t('docsPage.piiMasking.not1')}</li>
          <li>{t('docsPage.piiMasking.not2')}</li>
          <li>{t('docsPage.piiMasking.not3')}</li>
        </ul>
      </Section>
    </DocsArticle>
  );
}
