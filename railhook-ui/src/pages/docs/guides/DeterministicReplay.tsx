import { useTranslation } from 'react-i18next';
import {
  CodeBlock,
  DefinitionList,
  Diagram,
  DocsArticle,
  DocsTitle,
  Note,
  Route,
  Section,
  SketchBox,
  SketchChip,
  SketchEdge,
  SketchText,
} from '../primitives';
import { replaySamples } from '../samples';

/**
 * Two endpoints with "replay" in the path do different things, and the page
 * used to describe only one of them. `/deliveries/{id}/replay` puts the same
 * Delivery back on its ladder; `/projects/{id}/replay` builds new Deliveries
 * from stored Events — which is what CONTEXT.md calls a Replay, and what the
 * dashboard calls the Time Machine. Naming both is the point of the first
 * section: everything else here is a consequence of which one you picked.
 */
/**
 * The distinction the whole page rests on, drawn: a retry is the same row
 * moving again, a replay is a second row that did not exist before. The
 * sequence numbers are what make it visible — the replayed delivery queues
 * behind whatever is live rather than reclaiming the original's place.
 */
function RetryVersusReplayDiagram() {
  const { t } = useTranslation();

  return (
    <Diagram
      viewBox="0 0 440 280"
      label={t('docsPage.deterministicReplay.diagAlt')}
      caption={t('docsPage.deterministicReplay.diagCaption')}
    >
      {/* The page exists because two endpoints have "replay" in the path and do different
          things. Naming each lane's endpoint on the lane answers "which one do I call?"
          where the reader is looking, instead of two sections down. */}
      <SketchText x={12} y={20} anchor="start" size={9.5} mono>
        {t('docsPage.deterministicReplay.diagRetryLane').toUpperCase()}
      </SketchText>
      <SketchChip x={258} y={20}>{'POST /deliveries/{id}/replay'}</SketchChip>
      <SketchEdge d="M286,46 C318,28 318,88 292,74" tone="retry" />
      <SketchText x={318} y={62} anchor="start" size={12} tone="retry">
        {t('docsPage.deterministicReplay.diagNextAttempt')}
      </SketchText>
      <SketchBox x={136} y={34} w={150} role={t('docsPage.concepts.delivery')} sub="d1 · seq 12" align="start" tone="retry" />

      <SketchText x={12} y={104} anchor="start" size={9.5} mono>
        {t('docsPage.deterministicReplay.diagReplayLane').toUpperCase()}
      </SketchText>
      <SketchChip x={258} y={100}>{'POST /projects/{id}/replay'}</SketchChip>
      <SketchEdge d="M126,172 C150,172 156,142 176,142" />
      <SketchEdge d="M126,178 C150,178 156,214 176,214" tone="ok" />
      <SketchBox x={12} y={152} w={114} label={t('docsPage.concepts.event')} />
      <SketchBox x={180} y={120} w={140} role={t('docsPage.concepts.delivery')} sub="d1 · seq 12" align="start" tone="idle" />
      <SketchBox x={180} y={192} w={140} role={t('docsPage.concepts.delivery')} sub="d2 · seq 41" align="start" tone="ok" />
      <SketchText x={328} y={147} anchor="start" size={12}>
        {t('docsPage.deterministicReplay.diagUnchanged')}
      </SketchText>
      <SketchText x={328} y={219} anchor="start" size={12} tone="ok">
        {t('docsPage.deterministicReplay.diagNew')}
      </SketchText>

      <SketchText x={220} y={266} size={13}>
        {t('docsPage.deterministicReplay.diagFoot')}
      </SketchText>
    </Diagram>
  );
}

export default function DeterministicReplay() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle
        title={t('docsPage.deterministicReplay.title')}
        lede={t('docsPage.deterministicReplay.subtitle')}
      />

      <Section
        title={t('docsPage.deterministicReplay.retryVsReplay')}
        description={t('docsPage.deterministicReplay.retryVsReplayDesc')}
      >
        <RetryVersusReplayDiagram />
      </Section>

      <Section
        title={t('docsPage.deterministicReplay.oneDelivery')}
        description={t('docsPage.deterministicReplay.oneDeliveryDesc')}
      >
        <Route method="POST" path="/api/v1/deliveries/{id}/replay" />
      </Section>

      <Section title={t('docsPage.deterministicReplay.dryRunTitle')} description={t('docsPage.deterministicReplay.dryRunDesc')}>
        <Route method="POST" path="/api/v1/deliveries/{id}/replay?dryRun=true" />
        <CodeBlock code={replaySamples.dryRun} label="json" />
      </Section>

      <Section
        title={t('docsPage.deterministicReplay.replayFromAttemptTitle')}
        description={t('docsPage.deterministicReplay.replayFromAttemptDesc')}
      >
        <Route method="POST" path="/api/v1/deliveries/{id}/replay?fromAttempt=2" />
        <Note label={t('docsPage.deterministicReplay.whenLabel')}>
          {t('docsPage.deterministicReplay.replayFromAttemptNote')}
        </Note>
      </Section>

      <Section
        title={t('docsPage.deterministicReplay.timeMachineTitle')}
        description={t('docsPage.deterministicReplay.timeMachineDesc')}
      >
        <Route method="POST" path="/api/v1/projects/{projectId}/replay" />
        <Route method="POST" path="/api/v1/projects/{projectId}/replay/estimate" />
      </Section>

      <Section
        title={t('docsPage.deterministicReplay.idempotencyKeyHeader')}
        description={t('docsPage.deterministicReplay.idempotencyKeyHeaderDesc')}
      >
        <CodeBlock code={replaySamples.header} label="http" />
      </Section>

      <Section
        title={t('docsPage.deterministicReplay.idempotencyPoliciesTitle')}
        description={t('docsPage.deterministicReplay.idempotencyPoliciesDesc')}
      >
        <DefinitionList
          items={[
            { term: 'NONE', definition: t('docsPage.deterministicReplay.policyNone') },
            { term: 'AUTO', definition: t('docsPage.deterministicReplay.policyAuto') },
            { term: 'REQUIRED', definition: t('docsPage.deterministicReplay.policyRequired') },
          ]}
        />
      </Section>
    </DocsArticle>
  );
}
