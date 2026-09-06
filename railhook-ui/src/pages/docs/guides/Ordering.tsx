import { useTranslation } from 'react-i18next';
import {
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
 * Ordering is the feature people most often assume is on, and most often assume
 * means more than it does. Three things this page exists to state: it is
 * opt-in, it is outgoing only, and the Gap timeout will break the guarantee
 * rather than block an endpoint forever.
 */

const CELL_W = 72;
const CELL_H = 44;
const ROW_Y = 44;
const GAP_X = 8;

/** One endpoint's sequence, with the Gap that is holding 44 back. */
function SequenceStrip() {
  const { t } = useTranslation();
  const cell = (i: number) => GAP_X + i * (CELL_W + 10);

  return (
    <Diagram
      viewBox="0 0 420 132"
      label={t('docsPage.ordering.diagramAlt')}
      caption={t('docsPage.ordering.diagramCaption')}
    >
      <SketchBox x={cell(0)} y={ROW_Y} w={CELL_W} h={CELL_H} label="41" sub={t('docsPage.ordering.cellDone')} tone="ok" />
      <SketchBox x={cell(1)} y={ROW_Y} w={CELL_W} h={CELL_H} label="42" sub={t('docsPage.ordering.cellDone')} tone="ok" />
      <SketchBox x={cell(2)} y={ROW_Y} w={CELL_W} h={CELL_H} label="43" sub={t('docsPage.ordering.cellOpen')} tone="halt" />
      <SketchBox x={cell(3)} y={ROW_Y} w={CELL_W} h={CELL_H} label="44" sub={t('docsPage.ordering.cellWaiting')} tone="idle" />

      <SketchText x={cell(2) + CELL_W / 2} y={30} tone="halt">
        {t('docsPage.ordering.diagramGap')}
      </SketchText>

      <SketchEdge d={`M${cell(3) + CELL_W / 2},${ROW_Y + CELL_H} V104`} tone="idle" dashed />
      <SketchText x={cell(3) + CELL_W / 2} y={120} tone="idle">
        {t('docsPage.ordering.diagramParked')}
      </SketchText>
    </Diagram>
  );
}

export default function Ordering() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.ordering.title')} lede={t('docsPage.ordering.subtitle')} />

      <Section title={t('docsPage.ordering.default')} description={t('docsPage.ordering.defaultDesc')}>
        <Note label={t('docsPage.ordering.scopeLabel')}>{t('docsPage.ordering.scopeDesc')}</Note>
      </Section>

      <Section title={t('docsPage.ordering.howItWorks')} description={t('docsPage.ordering.howItWorksDesc')}>
        <SequenceStrip />
        <DefinitionList
          items={[
            { term: t('docsPage.ordering.termSequence'), definition: t('docsPage.ordering.termSequenceDesc') },
            { term: t('docsPage.ordering.termBuffer'), definition: t('docsPage.ordering.termBufferDesc') },
            { term: t('docsPage.ordering.termGap'), definition: t('docsPage.ordering.termGapDesc') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.ordering.gapTimeout')} description={t('docsPage.ordering.gapTimeoutDesc')}>
        <Note label={t('docsPage.ordering.gapTimeoutLabel')}>{t('docsPage.ordering.gapTimeoutNote')}</Note>
      </Section>

      <Section title={t('docsPage.ordering.cost')} description={t('docsPage.ordering.costDesc')}>
        <DefinitionList
          items={[
            { term: t('docsPage.ordering.costThroughput'), definition: t('docsPage.ordering.costThroughputDesc') },
            { term: t('docsPage.ordering.costHeadOfLine'), definition: t('docsPage.ordering.costHeadOfLineDesc') },
            { term: t('docsPage.ordering.costReplay'), definition: t('docsPage.ordering.costReplayDesc') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.ordering.guarantees')} description={t('docsPage.ordering.guaranteesDesc')}>
        <ul className="max-w-2xl list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground">
          <li>{t('docsPage.ordering.guarantee1')}</li>
          <li>{t('docsPage.ordering.guarantee2')}</li>
          <li>{t('docsPage.ordering.guarantee3')}</li>
        </ul>
      </Section>
    </DocsArticle>
  );
}
