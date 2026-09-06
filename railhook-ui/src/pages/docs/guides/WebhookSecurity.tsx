import { useTranslation } from 'react-i18next';
import {
  CodeBlock,
  CodeSample,
  DefinitionList,
  Diagram,
  DocsArticle,
  DocsTitle,
  Note,
  Prose,
  Section,
  SketchBox,
  SketchChip,
  SketchEdge,
  SketchText,
  SubSection,
} from '../primitives';
import type { SampleLanguage } from '../primitives';
import {
  challengeSamples, rotationSample, signatureSamples, standardSignatureSamples,
} from '../samples';

/**
 * The header list is every `X-` header `OutgoingAttemptStore` sets, in the order
 * it sets them. It used to name four of the six, which is worse than naming
 * none: a reader who deduplicates on `Idempotency-Key` had no way to learn it
 * exists. The three `webhook-*` headers the same request carries are a second
 * signature over the same bytes, not more metadata, so they are listed in the
 * Standard Webhooks section rather than mixed in here.
 */
/**
 * What is signed, what travels, and what the receiver does with it.
 *
 * The drawing exists for one detail that prose keeps losing: the signature is
 * over `<t>.<raw body>`, not over the body — a verifier that hashes the body
 * alone gets a different digest every time and never finds out why.
 */
function SigningDiagram() {
  const { t } = useTranslation();

  return (
    <Diagram
      viewBox="0 0 440 256"
      label={t('docsPage.security.diagAlt')}
      caption={t('docsPage.security.diagCaption')}
    >
      <SketchEdge d="M158,40 H278" />
      <SketchChip x={218} y={24}>POST</SketchChip>

      <SketchBox x={14} y={18} w={144} h={44} label="Railhook" />
      <SketchBox x={282} y={18} w={144} h={44} role={t('docsPage.security.diagReceiver')} sub="api.acme.io" align="start" />

      {/* what actually travels */}
      <SketchEdge d="M354,62 V78" />
      <SketchBox x={54} y={82} w={332} h={40} />
      <SketchText x={220} y={107} size={12.5} mono>
        X-Signature: t=&lt;unix-ms&gt;,v1=&lt;hex&gt;
      </SketchText>
      <SketchText x={220} y={140} size={12}>
        {t('docsPage.security.diagSigned')}
      </SketchText>

      {/* what the receiver does with it */}
      <SketchEdge d="M354,148 V166" />
      <SketchText x={220} y={190} size={13}>
        {t('docsPage.security.diagStep1')}
      </SketchText>
      <SketchText x={220} y={214} size={13}>
        {t('docsPage.security.diagStep2')}
      </SketchText>
      <SketchText x={220} y={238} size={13} tone="halt">
        {t('docsPage.security.diagStep3')}
      </SketchText>
    </Diagram>
  );
}

export default function WebhookSecurity({
  language,
  onLanguageChange,
}: {
  language: SampleLanguage;
  onLanguageChange: (language: SampleLanguage) => void;
}) {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.security.title')} lede={t('docsPage.security.subtitle')} />

      <Section title={t('docsPage.security.sigVerification')} description={t('docsPage.security.sigVerificationDesc')}>
        <SigningDiagram />
        <SubSection title={t('docsPage.security.headers')}>
          <DefinitionList
            items={[
              { term: 'X-Signature', definition: t('docsPage.security.headerSignature') },
              { term: 'X-Event-Id', definition: t('docsPage.security.headerEventId') },
              { term: 'X-Delivery-Id', definition: t('docsPage.security.headerDeliveryId') },
              { term: 'X-Timestamp', definition: t('docsPage.security.headerTimestamp') },
              { term: 'X-Sequence-Number', definition: t('docsPage.security.headerSequence') },
              { term: 'Idempotency-Key', definition: t('docsPage.security.headerIdempotency') },
            ]}
          />
        </SubSection>

        <SubSection title={t('docsPage.security.verificationExamples')}>
          <CodeSample samples={signatureSamples} language={language} onLanguageChange={onLanguageChange} />
        </SubSection>

        <SubSection title={t('docsPage.security.rotation')}>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
            <Prose>{t('docsPage.security.rotationDesc')}</Prose>
          </p>
          <CodeBlock code={rotationSample} label="http" />
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
            <Prose>{t('docsPage.security.rotationAfter')}</Prose>
          </p>
        </SubSection>

        <Note label={t('docsPage.security.pitfallsLabel')}>{t('docsPage.security.pitfallsDesc')}</Note>
      </Section>

      <Section title={t('docsPage.security.standard')} description={t('docsPage.security.standardDesc')}>
        <SubSection title={t('docsPage.security.standardHeaders')}>
          <DefinitionList
            items={[
              { term: 'webhook-id', definition: t('docsPage.security.standardHeaderId') },
              { term: 'webhook-timestamp', definition: t('docsPage.security.standardHeaderTimestamp') },
              { term: 'webhook-signature', definition: t('docsPage.security.standardHeaderSignature') },
            ]}
          />
        </SubSection>

        <SubSection title={t('docsPage.security.standardChoosing')}>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
            <Prose>{t('docsPage.security.standardChoosingDesc')}</Prose>
          </p>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
            <Prose>{t('docsPage.security.standardChoosingWhere')}</Prose>
          </p>
        </SubSection>

        <SubSection title={t('docsPage.security.standardSecret')}>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
            <Prose>{t('docsPage.security.standardSecretDesc')}</Prose>
          </p>
        </SubSection>

        <SubSection title={t('docsPage.security.verificationExamples')}>
          <CodeSample
            samples={standardSignatureSamples}
            language={language}
            onLanguageChange={onLanguageChange}
          />
        </SubSection>

        <Note label={t('docsPage.security.standardPitfallsLabel')}>
          {t('docsPage.security.standardPitfallsDesc')}
        </Note>
      </Section>

      <Section
        title={t('docsPage.security.endpointVerification')}
        description={t('docsPage.security.endpointVerificationDesc')}
      >
        <SubSection title={t('docsPage.security.challengeRequest')}>
          <CodeBlock code={challengeSamples.request} label="http" />
        </SubSection>
        <SubSection title={t('docsPage.security.expectedResponse')}>
          <CodeBlock code={challengeSamples.response} label="http" />
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
            <Prose>{t('docsPage.security.expectedResponseDesc')}</Prose>
          </p>
        </SubSection>
      </Section>
    </DocsArticle>
  );
}
