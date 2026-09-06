import { useTranslation } from 'react-i18next';
import { DefinitionList, DocsArticle, DocsTitle, Note, Section } from '../primitives';

/**
 * Everything on an Endpoint that is about trust, in one place — because they
 * are configured on one screen and are otherwise scattered across the signature
 * guide, the operations runbook and nowhere at all.
 *
 * `allowedSourceIps` gets its own callout. The field's *name* reads like a
 * control; its hint in the create dialog says "for documentation only", and
 * nothing reads the column to make a decision. Someone skimming the field list
 * would assume otherwise.
 */
export default function EndpointSecurity() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle
        title={t('docsPage.endpointSecurity.title')}
        lede={t('docsPage.endpointSecurity.subtitle')}
      />

      <Section
        title={t('docsPage.endpointSecurity.rotation')}
        description={t('docsPage.endpointSecurity.rotationDesc')}
      >
        <DefinitionList
          items={[
            { term: t('docsPage.endpointSecurity.rotStep1'), definition: t('docsPage.endpointSecurity.rotStep1Desc') },
            { term: t('docsPage.endpointSecurity.rotStep2'), definition: t('docsPage.endpointSecurity.rotStep2Desc') },
            { term: t('docsPage.endpointSecurity.rotStep3'), definition: t('docsPage.endpointSecurity.rotStep3Desc') },
          ]}
        />
        <Note label={t('docsPage.endpointSecurity.rotTimingLabel')}>
          {t('docsPage.endpointSecurity.rotTimingDesc')}
        </Note>
      </Section>

      <Section
        title={t('docsPage.endpointSecurity.mtls')}
        description={t('docsPage.endpointSecurity.mtlsDesc')}
      >
        <DefinitionList
          items={[
            { term: t('docsPage.endpointSecurity.mtlsCert'), definition: t('docsPage.endpointSecurity.mtlsCertDesc') },
            { term: t('docsPage.endpointSecurity.mtlsKey'), definition: t('docsPage.endpointSecurity.mtlsKeyDesc') },
            { term: t('docsPage.endpointSecurity.mtlsCa'), definition: t('docsPage.endpointSecurity.mtlsCaDesc') },
          ]}
        />
      </Section>

      <Section
        title={t('docsPage.endpointSecurity.verification')}
        description={t('docsPage.endpointSecurity.verificationDesc')}
      >
        <DefinitionList
          items={[
            { term: t('docsPage.endpointSecurity.verifyChallenge'), definition: t('docsPage.endpointSecurity.verifyChallengeDesc') },
            { term: t('docsPage.endpointSecurity.verifySkip'), definition: t('docsPage.endpointSecurity.verifySkipDesc') },
          ]}
        />
      </Section>

      <Section
        title={t('docsPage.endpointSecurity.ssrf')}
        description={t('docsPage.endpointSecurity.ssrfDesc')}
      >
        <Note label={t('docsPage.endpointSecurity.ssrfLabel')}>
          {t('docsPage.endpointSecurity.ssrfNote')}
        </Note>
      </Section>

      <Section title={t('docsPage.endpointSecurity.allowedIps')}>
        <Note label={t('docsPage.endpointSecurity.allowedIpsLabel')}>
          {t('docsPage.endpointSecurity.allowedIpsDesc')}
        </Note>
      </Section>

      <Section
        title={t('docsPage.endpointSecurity.encryption')}
        description={t('docsPage.endpointSecurity.encryptionDesc')}
      />
    </DocsArticle>
  );
}
