import { useTranslation } from 'react-i18next';
import {
  DefinitionList,
  Diagram,
  DocsArticle,
  DocsTitle,
  Note,
  Section,
  SketchBox,
  SketchChip,
  SketchEdge,
  SketchText,
} from '../primitives';

/**
 * The behaviour the whole product is built around, and the one thing no
 * generated page can state: `openapi.yaml` describes the replay endpoint, but
 * nothing in it says when a delivery is abandoned.
 *
 * The numbers below are the two ladders in `RetryLadderDefaults`, which is
 * their single declaration — the delays, the attempt counts and the reason the
 * two directions differ all come from its javadoc. If that class changes, this
 * page is wrong, and there is no test that will say so.
 */

/* Geometry of the state machine, in viewBox units. */
const SPINE_X = 220;
const PENDING_Y = 24;
const PROCESSING_Y = 140;
const TERMINAL_Y = 280;
const BOX_H = 46;

/**
 * The five statuses and every transition between them the platform makes on its
 * own. The manual ones are left out and said in prose below: an edge from DLQ
 * back to PENDING would have to cross the drawing to say something a sentence
 * says better.
 */
function DeliveryStateMachine() {
  const { t } = useTranslation();

  return (
    <Diagram
      viewBox="0 0 440 348"
      label={t('docsPage.retries.diagramAlt')}
      caption={t('docsPage.retries.diagramCaption')}
    >
      {/* claimed: PENDING → PROCESSING */}
      <SketchEdge d={`M${SPINE_X},${PENDING_Y + BOX_H} V${PROCESSING_Y - 6}`} />
      <SketchText x={228} y={110} anchor="start">
        {t('docsPage.retries.edgeClaim')}
      </SketchText>

      {/* 2xx: PROCESSING → SUCCESS */}
      <SketchEdge d={`M185,${PROCESSING_Y + BOX_H} C185,235 84,225 84,${TERMINAL_Y - 6}`} tone="ok" />
      <SketchText x={110} y={226} tone="ok">
        {t('docsPage.retries.edge2xx')}
      </SketchText>

      {/* nothing left to try: PROCESSING → FAILED */}
      <SketchEdge d={`M${SPINE_X},${PROCESSING_Y + BOX_H} V${TERMINAL_Y - 6}`} tone="halt" />
      <SketchText x={228} y={232} anchor="start" tone="halt">
        {t('docsPage.retries.edge4xx')}
      </SketchText>

      {/* ladder exhausted: PROCESSING → DLQ */}
      <SketchEdge d={`M255,${PROCESSING_Y + BOX_H} C255,235 320,225 320,${TERMINAL_Y - 6}`} tone="halt" />
      <SketchText x={358} y={210} tone="halt">
        {t('docsPage.retries.edgeExhausted')}
      </SketchText>

      {/* The age cap is a second way into the DLQ that starts nowhere in
          particular — a delivery that stopped moving rather than one that
          failed. A stub says that without an edge across the whole drawing. */}
      <SketchEdge d={`M392,254 V${TERMINAL_Y - 6}`} tone="halt" dashed />
      <SketchText x={392} y={246} tone="halt">
        {t('docsPage.retries.edgeAgeCap')}
      </SketchText>

      {/* retryable failure: PROCESSING → PENDING, round the right */}
      {/* The wait is the whole subject of this page and the state machine could not show it:
          "retryable failure" sent the reader back to PENDING with no hint that the return
          trip takes a minute the first time and six hours the fifth. */}
      <SketchEdge d={`M295,163 H400 V47 H${SPINE_X + 82}`} tone="retry" />
      <SketchText x={392} y={98} anchor="end" size={12} tone="retry">
        {t('docsPage.retries.edgeRetry')}
      </SketchText>
      {/* Short on purpose: the gap between PROCESSING and the loop's vertical run is about a
          hundred units, and the full ladder is spelled out in the list below. What the
          drawing owes the reader here is that the return trip is not instant. */}
      <SketchChip x={345} y={120} tone="retry">1m → 24h</SketchChip>

      {/* deferral: PROCESSING → PENDING, round the left. Dashed, because
          nothing was sent and no attempt was spent. */}
      <SketchEdge d={`M145,163 H45 V47 H${SPINE_X - 82}`} tone="idle" dashed />
      <SketchText x={51} y={104} anchor="start">
        {t('docsPage.retries.edgeDefer')}
      </SketchText>

      <SketchBox x={145} y={PENDING_Y} w={150} label="PENDING" tone="idle" />
      <SketchBox x={145} y={PROCESSING_Y} w={150} label="PROCESSING" tone="retry" />
      <SketchBox x={20} y={TERMINAL_Y} w={128} label="SUCCESS" tone="ok" />
      <SketchBox x={156} y={TERMINAL_Y} w={128} label="FAILED" tone="halt" />
      {/* Both names, because the API status really is DLQ and the screen really says Failed
          Messages — a reader meets one of them in a payload and the other in the product. */}
      <SketchBox x={292} y={TERMINAL_Y} w={128} label="DLQ" sub="Failed Messages" tone="halt" />
    </Diagram>
  );
}

export default function Retries() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.retries.title')} lede={t('docsPage.retries.subtitle')} />

      <Section title={t('docsPage.retries.statuses')}>
        <DeliveryStateMachine />
        <DefinitionList
          items={[
            { term: 'PENDING', definition: t('docsPage.retries.statusPending') },
            { term: 'PROCESSING', definition: t('docsPage.retries.statusProcessing') },
            { term: 'SUCCESS', definition: t('docsPage.retries.statusSuccess') },
            { term: 'FAILED', definition: t('docsPage.retries.statusFailed') },
            { term: 'DLQ', definition: t('docsPage.retries.statusDlq') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.retries.retryable')} description={t('docsPage.retries.retryableDesc')} />

      <Section title={t('docsPage.retries.ladders')} description={t('docsPage.retries.laddersDesc')}>
        <DefinitionList
          items={[
            { term: t('docsPage.retries.ladderOutgoing'), definition: t('docsPage.retries.ladderOutgoingDesc') },
            { term: t('docsPage.retries.ladderIncoming'), definition: t('docsPage.retries.ladderIncomingDesc') },
          ]}
        />
        <Note label={t('docsPage.retries.whyDifferLabel')}>{t('docsPage.retries.whyDifferDesc')}</Note>
        <Note label={t('docsPage.retries.notCountedLabel')}>{t('docsPage.retries.notCountedDesc')}</Note>
      </Section>

      <Section title={t('docsPage.retries.timeouts')} description={t('docsPage.retries.timeoutsDesc')} />

      <Section title={t('docsPage.retries.ageCap')} description={t('docsPage.retries.ageCapDesc')} />

      <Section title={t('docsPage.retries.dlqTitle')} description={t('docsPage.retries.dlqDesc')}>
        <Note label={t('docsPage.retries.dlqRetryLabel')}>{t('docsPage.retries.dlqRetryDesc')}</Note>
        <Note label={t('docsPage.retries.replayLabel')}>{t('docsPage.retries.replayDesc')}</Note>
      </Section>
    </DocsArticle>
  );
}
