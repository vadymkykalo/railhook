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
  SketchChip,
  SketchEdge,
  SketchText,
  StepRow,
} from '../primitives';
import { rulesSample } from '../samples';

/**
 * The ordering fact, which is the whole reason DROP behaves the way it does:
 * rules are evaluated before subscriptions are looked up, so a dropped event
 * has no deliveries to cancel — none were ever created.
 */
function RuleOrderDiagram() {
  const { t } = useTranslation();

  return (
    <Diagram
      viewBox="0 0 440 248"
      maxWidth={560}
      label={t('docsPage.rulesEngine.diagAlt')}
      caption={t('docsPage.rulesEngine.diagCaption')}
    >
      <SketchEdge d="M180,70 V96" />
      <SketchEdge d="M180,154 V182" />

      {/* DROP leaves the pipeline; the other three change the event and let it carry on.
          The earlier drawing named only DROP, so a reader could not tell whether TAG was a
          terminal action too. */}
      <SketchEdge d="M264,125 H306" tone="halt" />
      <SketchChip x={285} y={110} tone="halt" leaderFrom={125}>DROP</SketchChip>

      <SketchBox
        x={96}
        y={22}
        w={168}
        h={48}
        role={t('docsPage.concepts.event')}
        sub="order.created"
        align="start"
      />
      <SketchBox
        x={96}
        y={100}
        w={168}
        h={54}
        role={t('docsPage.rulesEngine.diagRules')}
        sub="ROUTE · TRANSFORM · TAG"
        align="start"
      />
      <SketchBox x={310} y={100} w={118} h={54} label={t('docsPage.rulesEngine.diagStop')} tone="halt" />
      <SketchBox
        x={96}
        y={186}
        w={168}
        h={48}
        role={t('docsPage.rulesEngine.diagSubs')}
        sub={t('docsPage.rulesEngine.diagSubsSub')}
        align="start"
        tone="ok"
      />

      <SketchText x={310} y={200} anchor="start" size={11}>
        {t('docsPage.rulesEngine.diagFoot')}
      </SketchText>
    </Diagram>
  );
}

export default function RulesEngine() {
  const { t } = useTranslation();

  const steps = [
    { label: t('docsPage.rulesEngine.step1'), desc: t('docsPage.rulesEngine.step1Desc') },
    { label: t('docsPage.rulesEngine.step2'), desc: t('docsPage.rulesEngine.step2Desc') },
    { label: t('docsPage.rulesEngine.step3'), desc: t('docsPage.rulesEngine.step3Desc') },
    { label: t('docsPage.rulesEngine.step4'), desc: t('docsPage.rulesEngine.step4Desc') },
  ];

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.rulesEngine.title')} lede={t('docsPage.rulesEngine.subtitle')} />

      <Section title={t('docsPage.rulesEngine.howItWorks')}>
        <RuleOrderDiagram />
        <StepRow steps={steps} />
        <Note label={t('docsPage.rulesEngine.importantLabel')}>{t('docsPage.rulesEngine.importantBody')}</Note>
      </Section>

      <Section title={t('docsPage.rulesEngine.concepts')}>
        <DefinitionList
          items={[
            { term: t('docsPage.rulesEngine.conceptRule'), definition: t('docsPage.rulesEngine.conceptRuleDesc') },
            {
              term: t('docsPage.rulesEngine.conceptCondition'),
              definition: t('docsPage.rulesEngine.conceptConditionDesc'),
            },
            { term: t('docsPage.rulesEngine.conceptAction'), definition: t('docsPage.rulesEngine.conceptActionDesc') },
            {
              term: t('docsPage.rulesEngine.conceptPriority'),
              definition: t('docsPage.rulesEngine.conceptPriorityDesc'),
            },
          ]}
        />
      </Section>

      <Section title={t('docsPage.rulesEngine.actions')}>
        <DefinitionList
          items={[
            { term: 'ROUTE', definition: t('docsPage.rulesEngine.actionRoute') },
            { term: 'TRANSFORM', definition: t('docsPage.rulesEngine.actionTransform') },
            { term: 'TAG', definition: t('docsPage.rulesEngine.actionTag') },
            { term: 'DROP', definition: t('docsPage.rulesEngine.actionDrop') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.rulesEngine.exampleTitle')} description={t('docsPage.rulesEngine.exampleDesc')}>
        <CodeBlock code={rulesSample} label="bash" />
      </Section>
    </DocsArticle>
  );
}
