import { useTranslation } from 'react-i18next';
import {
  CodeBlock,
  DefinitionList,
  DocsArticle,
  DocsTitle,
  Note,
  Prose,
  Section,
} from '../primitives';

export default function IncomingWebhooks() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.incomingWebhooks.title')} lede={t('docsPage.incomingWebhooks.subtitle')} />

      <Section title={t('docsPage.incomingWebhooks.overview')} description={t('docsPage.incomingWebhooks.overviewDesc1')}>
        <Note label={t('docsPage.incomingWebhooks.forwardLabel')}>{t('docsPage.incomingWebhooks.forwardDesc')}</Note>
      </Section>

      <Section title={t('docsPage.incomingWebhooks.ingressUrl')} description={t('docsPage.incomingWebhooks.ingressUrlDesc')}>
        <CodeBlock code={'POST https://your-api.com/ingress/{token}'} label="http" />
      </Section>

      {/* The ingress answers a provider, not a person: each code is what that
          provider's own retry logic will see, which is why they are listed
          here rather than left to the generated reference. */}
      <Section title={t('docsPage.incomingWebhooks.responses')}>
        <DefinitionList
          items={[
            { term: '202', definition: t('docsPage.incomingWebhooks.resp202') },
            { term: '401', definition: t('docsPage.incomingWebhooks.resp401') },
            { term: '404', definition: t('docsPage.incomingWebhooks.resp404') },
            { term: '410', definition: t('docsPage.incomingWebhooks.resp410') },
            { term: '413', definition: t('docsPage.incomingWebhooks.resp413') },
            { term: '429', definition: t('docsPage.incomingWebhooks.resp429') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.incomingWebhooks.verificationModes')}>
        <DefinitionList
          items={[
            { term: 'NONE', definition: t('docsPage.incomingWebhooks.modeNone') },
            { term: 'HMAC_GENERIC', definition: t('docsPage.incomingWebhooks.modeHmac') },
            { term: 'PROVIDER', definition: t('docsPage.incomingWebhooks.modeProvider') },
          ]}
        />
        <p className="text-sm text-muted-foreground"><Prose>{t('docsPage.incomingWebhooks.providerTypes')}</Prose></p>
        <Note label={t('docsPage.incomingWebhooks.dedupLabel')}>{t('docsPage.incomingWebhooks.dedupDesc')}</Note>
      </Section>

      <Section title={t('docsPage.incomingWebhooks.destinations')} description={t('docsPage.incomingWebhooks.destinationsDesc')} />
    </DocsArticle>
  );
}
