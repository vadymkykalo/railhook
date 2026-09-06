import { useTranslation } from 'react-i18next';
import {
  CodeBlock,
  DefinitionList,
  Diagram,
  DocsArticle,
  DocsTitle,
  Note,
  Section,
  SketchBox,
  SketchEdge,
  SketchText,
} from '../primitives';

/**
 * The one thing a spec cannot say about transformations: *when* they run.
 *
 * `openapi.yaml` describes the CRUD and the preview endpoint. It cannot say
 * that the template is resolved inside the Attempt, after the Claim and before
 * the signature — which is what makes a broken template retryable rather than
 * a silent raw-payload leak. That ordering is `AttemptRunner`'s invariant 4.
 */

const BOX_W = 92;
const BOX_H = 44;
const Y = 40;

/** Where the template is resolved, relative to the two things either side of it. */
function TransformPipeline() {
  const { t } = useTranslation();

  return (
    <Diagram
      viewBox="0 0 460 130"
      label={t('docsPage.transformations.diagramAlt')}
      caption={t('docsPage.transformations.diagramCaption')}
    >
      <SketchBox x={8} y={Y} w={BOX_W} h={BOX_H} label={t('docsPage.transformations.stepClaim')} tone="idle" />
      <SketchEdge d={`M${8 + BOX_W},${Y + BOX_H / 2} H120`} />

      <SketchBox x={122} y={Y} w={BOX_W} h={BOX_H} label={t('docsPage.transformations.stepTransform')} tone="retry" />
      <SketchEdge d={`M${122 + BOX_W},${Y + BOX_H / 2} H234`} />

      <SketchBox x={236} y={Y} w={BOX_W} h={BOX_H} label={t('docsPage.transformations.stepSign')} tone="ok" />
      <SketchEdge d={`M${236 + BOX_W},${Y + BOX_H / 2} H348`} />

      <SketchBox x={350} y={Y} w={BOX_W} h={BOX_H} label={t('docsPage.transformations.stepSend')} tone="ok" />

      {/* The failure edge is the point of the drawing. */}
      <SketchEdge d={`M168,${Y + BOX_H} V104`} tone="halt" dashed />
      <SketchText x={176} y={108} anchor="start" tone="halt">
        {t('docsPage.transformations.diagramFail')}
      </SketchText>
    </Diagram>
  );
}

export default function Transformations() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle
        title={t('docsPage.transformations.title')}
        lede={t('docsPage.transformations.subtitle')}
      />

      <Section
        title={t('docsPage.transformations.template')}
        description={t('docsPage.transformations.templateDesc')}
      >
        <CodeBlock label={t('docsPage.transformations.templateLabel')} code={TEMPLATE_SAMPLE} />
        <Note label={t('docsPage.transformations.notJsLabel')}>
          {t('docsPage.transformations.notJsDesc')}
        </Note>
      </Section>

      <Section
        title={t('docsPage.transformations.whenItRuns')}
        description={t('docsPage.transformations.whenItRunsDesc')}
      >
        <TransformPipeline />
        <Note label={t('docsPage.transformations.rawLabel')}>
          {t('docsPage.transformations.rawDesc')}
        </Note>
      </Section>

      <Section
        title={t('docsPage.transformations.attaching')}
        description={t('docsPage.transformations.attachingDesc')}
      >
        <DefinitionList
          items={[
            { term: 'Subscription', definition: t('docsPage.transformations.attachSubscription') },
            { term: 'Rule → TRANSFORM', definition: t('docsPage.transformations.attachRule') },
            { term: 'Destination', definition: t('docsPage.transformations.attachDestination') },
            { term: 'Delivery', definition: t('docsPage.transformations.attachDelivery') },
          ]}
        />
      </Section>

      <Section
        title={t('docsPage.transformations.testing')}
        description={t('docsPage.transformations.testingDesc')}
      >
        <DefinitionList
          items={[
            { term: t('docsPage.transformations.previewTerm'), definition: t('docsPage.transformations.previewDesc') },
            { term: t('docsPage.transformations.dryRunTerm'), definition: t('docsPage.transformations.dryRunDesc') },
            { term: t('docsPage.transformations.studioTerm'), definition: t('docsPage.transformations.studioDesc') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.transformations.limits')}>
        <DefinitionList
          items={[
            { term: t('docsPage.transformations.limitMissing'), definition: t('docsPage.transformations.limitMissingDesc') },
            { term: t('docsPage.transformations.limitVersion'), definition: t('docsPage.transformations.limitVersionDesc') },
            { term: t('docsPage.transformations.limitMetric'), definition: t('docsPage.transformations.limitMetricDesc') },
          ]}
        />
      </Section>
    </DocsArticle>
  );
}

const TEMPLATE_SAMPLE = `{
  "id": "\${$.id}",
  "customer": {
    "email": "\${$.data.customer.email}",
    "plan": "\${$.data.subscription.plan_name}"
  },
  "amount_cents": "\${$.data.amount}",
  "source": "railhook"
}`;
