import { useTranslation } from 'react-i18next';
import {
  CodeBlock,
  CodeSample,
  DocsArticle,
  DocsTitle,
  Note,
  Prose,
  Route,
  Section,
  SubSection,
} from '../primitives';
import type { SampleLanguage } from '../primitives';
import { authSamples } from '../samples';

export default function Authentication({
  language,
  onLanguageChange,
}: {
  language: SampleLanguage;
  onLanguageChange: (language: SampleLanguage) => void;
}) {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.auth.title')} lede={t('docsPage.auth.subtitle')} />

      <Section title={t('docsPage.auth.jwtTitle')} description={t('docsPage.auth.jwtDesc')}>
        <Route method="POST" path="/api/v1/auth/login" />
        <CodeSample samples={authSamples.login} language={language} onLanguageChange={onLanguageChange} />
        <Note label={t('docsPage.auth.refreshLabel')}>{t('docsPage.auth.refreshDesc')}</Note>
        <SubSection title={t('docsPage.auth.usingJwt')}>
          <p className="text-sm text-muted-foreground"><Prose>{t('docsPage.auth.usingJwtDesc')}</Prose></p>
          <CodeBlock code={authSamples.bearer} label="http" />
        </SubSection>
      </Section>

      <Section title={t('docsPage.auth.apiKeyTitle')} description={t('docsPage.auth.apiKeyDesc')}>
        <SubSection title={t('docsPage.auth.headerFormat')}>
          <CodeBlock code={authSamples.apiKey} label="http" />
        </SubSection>
        <Note label={t('docsPage.auth.scopesLabel')}>{t('docsPage.auth.scopesDesc')}</Note>
        <Note label={t('docsPage.auth.securityNote')}>{t('docsPage.auth.securityNoteDesc')}</Note>
      </Section>
    </DocsArticle>
  );
}
