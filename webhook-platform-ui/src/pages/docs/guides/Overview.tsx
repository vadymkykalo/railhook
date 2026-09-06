import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import {
  DefinitionList,
  Diagram,
  DocsArticle,
  DocsTitle,
  Prose,
  Section,
  SketchBox,
  SketchChip,
  SketchEdge,
  SketchRail,
  SketchText,
  StepRow,
} from '../primitives';

/**
 * The one page that has to agree with CONTEXT.md word for word: it is where a
 * reader learns which noun means what, and a synonym introduced here spreads
 * through every other page.
 *
 * The flow row carries a description per stage rather than four bare labels,
 * because the labels alone said nothing the sidebar had not already said.
 */
/**
 * The two directions, as one picture.
 *
 * The version this replaces was six rounded boxes reading "Your system → Railhook →
 * Endpoint" and "A provider → Railhook → Destination", with the words "signs" and "verifies"
 * floating underneath. That is the paragraph beside it, drawn — the reader learns nothing
 * from the picture they did not learn from the sentence.
 *
 * What is actually worth drawing is the header. In both rows the same platform sits in the
 * middle and the same lifecycle runs underneath; the only thing that differs is which side of
 * Railhook the proof is on. Outgoing, Railhook writes `X-Signature` on the way out. Incoming,
 * it reads the provider's own header on the way in — and the chip says `Stripe-Signature`
 * rather than "verifies", because that is the string the reader will be looking at in their
 * own logs.
 *
 * The rail under each row is the design brief's signature element, and it carries the one
 * remaining difference between the directions: seven rungs against five.
 */
function DirectionsDiagram() {
  const { t } = useTranslation();

  return (
    <Diagram
      viewBox="0 0 440 266"
      maxWidth={600}
      label={t('docsPage.overview.diagDirAlt')}
      caption={t('docsPage.overview.diagDirCaption')}
    >
      {/* ── outgoing ─────────────────────────────────────────────── */}
      <SketchText x={8} y={12} anchor="start" size={9.5} mono>
        {t('docsPage.overview.outgoing').toUpperCase()}
      </SketchText>

      <SketchEdge d="M126,44 H164" />
      <SketchEdge d="M282,44 H316" />
      <SketchBox x={8} y={20} w={118} h={48} role={t('docsPage.overview.diagDirYourSystem')} sub="order.created" align="start" />
      <SketchBox x={168} y={20} w={110} h={48} label="Railhook" />
      <SketchBox x={320} y={20} w={112} h={48} role={t('docsPage.concepts.endpoint')} sub="api.acme.io" align="start" />
      <SketchChip x={299} y={82} leaderFrom={46}>X-Signature</SketchChip>

      <SketchRail x={168} y={112} w={214} attempts={7} />
      <SketchText x={390} y={116} anchor="start" size={10} mono>
        {t('docsPage.overview.diagDirAttemptsOut')}
      </SketchText>

      {/* ── incoming ─────────────────────────────────────────────── */}
      <SketchText x={8} y={148} anchor="start" size={9.5} mono>
        {t('docsPage.overview.incoming').toUpperCase()}
      </SketchText>

      <SketchEdge d="M126,180 H164" />
      <SketchEdge d="M282,180 H316" />
      <SketchBox x={8} y={156} w={118} h={48} role={t('docsPage.overview.diagDirProvider')} sub="stripe.com" align="start" />
      <SketchBox x={168} y={156} w={110} h={48} label="Railhook" />
      <SketchBox x={320} y={156} w={112} h={48} role={t('docsPage.concepts.destination')} sub="internal/orders" align="start" />
      <SketchChip x={145} y={218} leaderFrom={182}>Stripe-Signature</SketchChip>

      <SketchRail x={168} y={248} w={214} attempts={5} />
      <SketchText x={390} y={252} anchor="start" size={10} mono>
        {t('docsPage.overview.diagDirAttemptsIn')}
      </SketchText>
    </Diagram>
  );
}

/**
 * The thing people get wrong about webhook platforms: an event is not a send.
 *
 * One event becomes one delivery per matching subscription, and from that moment the
 * deliveries have nothing to do with each other. The three lanes carry their attempt
 * counters for exactly that reason — the earlier version showed three coloured boxes and
 * three status words, which asserted the independence without showing it. `1/7`, `3/7` and
 * `7/7` on the same event is the whole point, and it is also where the retry ladder first
 * appears to a reader.
 *
 * The third lane says "Failed Messages", not "DLQ". CONTEXT.md is explicit that DLQ is
 * vocabulary you have to already know, and a drawing that labels a state differently from
 * the screen it describes teaches the reader a word the product will never say back.
 */
function FanOutDiagram() {
  const { t } = useTranslation();

  const lane = (
    x: number,
    endpoint: string,
    tone: 'ok' | 'retry' | 'halt',
    attempts: string,
    status: string,
  ) => (
    <>
      <SketchBox x={x} y={152} w={124} h={50} label={t('docsPage.concepts.delivery')} sub={endpoint} tone={tone} />
      <SketchText x={x + 62} y={222} size={12} mono tone={tone}>
        {attempts}
      </SketchText>
      <SketchText x={x + 62} y={240} size={12} tone={tone}>
        {status}
      </SketchText>
    </>
  );

  return (
    <Diagram
      viewBox="0 0 440 274"
      maxWidth={560}
      label={t('docsPage.overview.diagFanAlt')}
      caption={t('docsPage.overview.diagFanCaption')}
    >
      <SketchEdge d="M198,66 C186,104 84,110 72,146" />
      <SketchEdge d="M220,66 V146" />
      <SketchEdge d="M242,66 C254,104 356,110 368,146" />

      <SketchBox x={140} y={18} w={160} h={48} label={t('docsPage.concepts.event')} sub="order.created" />

      {lane(10, 'endpoint A', 'ok', '1/7', t('docsPage.overview.diagFanDelivered'))}
      {lane(158, 'endpoint B', 'retry', '3/7', t('docsPage.overview.diagFanRetrying'))}
      {lane(306, 'endpoint C', 'halt', '7/7', t('docsPage.overview.diagFanAbandoned'))}

      <SketchText x={220} y={264} size={12}>
        {t('docsPage.overview.diagFanEach')}
      </SketchText>
    </Diagram>
  );
}

export default function Overview() {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.overview.title')} lede={t('docsPage.overview.subtitle')} />

      <Section title={t('docsPage.overview.whatIs')} description={t('docsPage.overview.whatIsDesc1')}>
        <p className="max-w-2xl leading-relaxed text-muted-foreground"><Prose>{t('docsPage.overview.whatIsDesc2')}</Prose></p>
      </Section>

      <Section title={t('docsPage.overview.directions')}>
        <DirectionsDiagram />
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="rounded-lg border border-rail bg-card p-4">
            <div className="mono-label mb-1.5">{t('docsPage.overview.outgoing')}</div>
            <p className="text-sm leading-relaxed text-muted-foreground"><Prose>{t('docsPage.overview.outgoingDesc')}</Prose></p>
          </div>
          <div className="rounded-lg border border-rail bg-card p-4">
            <div className="mono-label mb-1.5">{t('docsPage.overview.incoming')}</div>
            <p className="text-sm leading-relaxed text-muted-foreground"><Prose>{t('docsPage.overview.incomingDesc')}</Prose></p>
          </div>
        </div>
      </Section>

      <Section title={t('docsPage.overview.coreConcepts')}>
        <DefinitionList
          items={[
            { term: t('docsPage.concepts.event'), definition: t('docsPage.concepts.eventDesc') },
            { term: t('docsPage.concepts.endpoint'), definition: t('docsPage.concepts.endpointDesc') },
            { term: t('docsPage.concepts.subscription'), definition: t('docsPage.concepts.subscriptionDesc') },
            { term: t('docsPage.concepts.connection'), definition: t('docsPage.concepts.connectionDesc') },
            { term: t('docsPage.concepts.delivery'), definition: t('docsPage.concepts.deliveryDesc') },
            { term: t('docsPage.concepts.attempt'), definition: t('docsPage.concepts.attemptDesc') },
            { term: t('docsPage.concepts.source'), definition: t('docsPage.concepts.sourceDesc') },
            { term: t('docsPage.concepts.destination'), definition: t('docsPage.concepts.destinationDesc') },
            { term: t('docsPage.concepts.forward'), definition: t('docsPage.concepts.forwardDesc') },
          ]}
        />
      </Section>

      <Section title={t('docsPage.overview.eventFlow')}>
        <FanOutDiagram />
        <StepRow
          steps={[
            { label: t('docsPage.overview.flow1'), desc: t('docsPage.overview.flow1Desc') },
            { label: t('docsPage.overview.flow2'), desc: t('docsPage.overview.flow2Desc') },
            { label: t('docsPage.overview.flow3'), desc: t('docsPage.overview.flow3Desc') },
            { label: t('docsPage.overview.flow4'), desc: t('docsPage.overview.flow4Desc') },
          ]}
        />
      </Section>

      <p className="text-sm text-muted-foreground">
        <Link to="/docs/getting-started" className="text-primary hover:underline">
          {t('docsPage.overview.next')}
        </Link>
      </p>
    </DocsArticle>
  );
}
