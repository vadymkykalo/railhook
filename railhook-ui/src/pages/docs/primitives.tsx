import { createContext, useContext, useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowRight, Check, Copy } from 'lucide-react';
import SyntaxHighlight, { normalizeLanguage } from '../../components/SyntaxHighlight';
import { cn } from '../../lib/utils';
import { useCopyToClipboard } from '../../hooks/useCopyToClipboard';

/**
 * The docs voice, in one place.
 *
 * Two rules from the design brief drive everything here. The four status hues
 * are reserved for domain statuses, so nothing in the docs — not an HTTP verb,
 * not a callout, not a 4xx response code — borrows them; structure comes from
 * the rail and from mono weight instead. And "if the product said it, it is
 * mono": paths, verbs, field names, status codes and type names are all mono,
 * the prose around them is not.
 */

export function DocsArticle({ children }: { children: ReactNode }) {
  return <article className="space-y-12 pb-16">{children}</article>;
}

/**
 * Prose with the mono rule applied inside it.
 *
 * The brief at the top of this file says paths, verbs, field names, status codes
 * and type names are mono and the prose around them is not. Every heading and
 * code block obeyed that; the paragraphs did not, because they arrive as flat
 * i18n strings and a string cannot carry a span. So a sentence like "a delivery
 * to an endpoint that is neither VERIFIED nor SKIPPED fails terminally" printed
 * two status names, a header, and a full API path in the same body face as the
 * words around them, and the reader had to parse the sentence to find the parts
 * they were looking for.
 *
 * Authors mark those up in the locale file with backticks — `X-Signature`,
 * `POST /api/v1/events`, `VERIFIED` — and this splits on them. Backticks rather
 * than a richer markup because the translator has to reproduce them by hand in
 * uk.json, and one character that survives copy-paste is the most that can be
 * asked of a format nothing validates.
 *
 * Deliberately not a markdown renderer: this is the whole grammar, and a real
 * parser here would invite bold, links and lists into strings that then cannot
 * be typed or tested.
 */
export function Prose({ children, className }: { children: string; className?: string }) {
  return (
    <>
      {children.split(/`([^`]+)`/).map((part, i) =>
        i % 2 === 1 ? (
          <code
            key={i}
            className={cn(
              'whitespace-nowrap rounded border border-rail bg-secondary/60 px-1 py-px',
              'font-mono text-[0.86em] text-foreground',
              className,
            )}
          >
            {part}
          </code>
        ) : (
          part
        ),
      )}
    </>
  );
}

export function DocsTitle({ title, lede }: { title: string; lede?: string }) {
  return (
    <header className="space-y-3">
      <h1 className="text-3xl font-semibold tracking-[-0.02em] sm:text-headline">{title}</h1>
      {lede && <p className="max-w-2xl text-body-lg text-muted-foreground">{lede}</p>}
    </header>
  );
}

export function Section({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children?: ReactNode;
}) {
  return (
    <section className="space-y-4">
      <h2 className="text-title">{title}</h2>
      {description && (
        <p className="max-w-2xl leading-relaxed text-muted-foreground">
          <Prose>{description}</Prose>
        </p>
      )}
      {children}
    </section>
  );
}

export function SubSection({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="space-y-3">
      <h3 className="text-[15px] font-medium">{title}</h3>
      {children}
    </div>
  );
}

/**
 * A quiet aside. Deliberately not tinted: an amber "security note" next to an
 * amber "retrying" badge would teach the reader that the colour means nothing.
 */
export function Note({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rounded-lg border border-rail bg-secondary/50 p-4">
      <div className="mono-label mb-1.5">{label}</div>
      <div className="text-sm leading-relaxed text-muted-foreground">
        {typeof children === 'string' ? <Prose>{children}</Prose> : children}
      </div>
    </div>
  );
}

export function CodeBlock({ code, label, wrap = false }: { code: string; label?: string; wrap?: boolean }) {
  const { t } = useTranslation();
  const { copied, copy } = useCopyToClipboard();
  const caption = label ?? 'shell';

  /* Ink, not paper. A dark field is where machine output lives in this design —
     the landing's CodeBlock and the auth panel both use it, and `.surface-ink`
     redeclares the tokens locally so everything nested reads correctly on it.
     On `bg-secondary/40` a sample sat two percent off the page behind it, which
     is why a 25-line curl read as a wall of undifferentiated text.

     The other half of that wall was the flat monochrome, which `SyntaxHighlight`
     now breaks up. It takes the `label` as its language, so the caption and the
     colouring can never disagree: an unrecognised label highlights nothing
     rather than guessing wrong. */
  return (
    <figure className="surface-ink overflow-hidden rounded-lg border border-rail">
      <figcaption className="flex items-center justify-between gap-2 border-b border-rail px-3 py-1.5">
        <span className="mono-label truncate">{caption}</span>
        <button
          type="button"
          onClick={() => copy(code)}
          aria-label={copied ? t('common.copied') : t('common.copy')}
          className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
        >
          {copied ? <Check className="h-3.5 w-3.5" aria-hidden /> : <Copy className="h-3.5 w-3.5" aria-hidden />}
        </button>
      </figcaption>
      {/* `wrap` is for samples whose length is not the reader's choice — a raw.githubusercontent
          URL is 90 characters and there is no shorter form of it. Scrolling one of those
          sideways hides the end of the command, which is the part being copied. */}
      <pre className={cn('p-4', wrap ? 'whitespace-pre-wrap break-words' : 'overflow-x-auto')}>
        <code className="font-mono text-[13px] leading-relaxed text-foreground">
          <SyntaxHighlight code={code} language={normalizeLanguage(caption)} />
        </code>
      </pre>
    </figure>
  );
}

export type SampleLanguage = 'curl' | 'node' | 'python';

const LANGUAGE_LABELS: Record<SampleLanguage, string> = {
  curl: 'cURL',
  node: 'Node.js',
  python: 'Python',
};

/** A code block with the language switcher the whole docs site shares. */
export function CodeSample({
  samples,
  language,
  onLanguageChange,
}: {
  samples: Partial<Record<SampleLanguage, string>>;
  language: SampleLanguage;
  onLanguageChange: (language: SampleLanguage) => void;
}) {
  const available = (Object.keys(LANGUAGE_LABELS) as SampleLanguage[]).filter((l) => samples[l]);
  const active = samples[language] ? language : available[0];
  if (!active) return null;

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-1">
        {available.map((lang) => (
          <button
            key={lang}
            type="button"
            onClick={() => onLanguageChange(lang)}
            aria-pressed={active === lang}
            className={cn(
              'rounded-md px-2.5 py-1 font-mono text-[11px] uppercase tracking-[0.06em] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              active === lang
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
            )}
          >
            {LANGUAGE_LABELS[lang]}
          </button>
        ))}
      </div>
      <CodeBlock code={samples[active] as string} label={active === 'curl' ? 'bash' : active} />
    </div>
  );
}

/** An HTTP verb + path. Neutral by design — see the note at the top of this file. */
export function Route({ method, path }: { method: string; path: string }) {
  return (
    <div className="flex flex-wrap items-baseline gap-2.5">
      <span className="rounded border border-rail bg-secondary px-1.5 py-0.5 font-mono text-[11px] font-semibold uppercase tracking-[0.06em] text-foreground">
        {method}
      </span>
      <code className="break-all font-mono text-[13px] text-muted-foreground">{path}</code>
    </div>
  );
}

/** A dotted-rule definition list — the docs' answer to a two-column table. */
export function DefinitionList({ items }: { items: Array<{ term: string; definition: ReactNode }> }) {
  return (
    <dl className="divide-y divide-rail border-y border-rail">
      {items.map((item) => (
        <div key={item.term} className="grid gap-1 py-3 sm:grid-cols-[minmax(0,14rem)_1fr] sm:gap-4">
          <dt className="font-mono text-[13px] text-foreground">{item.term}</dt>
          <dd className="text-sm leading-relaxed text-muted-foreground">
            {typeof item.definition === 'string' ? <Prose>{item.definition}</Prose> : item.definition}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * A numbered sequence of stages, read left to right.
 *
 * The numbers are the whole justification for this component existing as its own
 * thing: a step row is an *ordered* sequence — "how a rule runs", "how an event
 * flows" — and nothing else in the docs is numbered. If a set of cards is a
 * menu rather than a pipeline, it is a grid, not this.
 *
 * Both copies of this used to be a flex row of independently-sized cards, which
 * meant four boxes on four different baselines with the arrows drifting to
 * wherever each card's own centre happened to fall. A grid gives every cell the
 * height of the tallest in its row for free, so the arrows — pinned to the
 * midpoint of the cell and floating in the gutter — land on one axis by
 * construction rather than by luck.
 *
 * The breakpoints are the second half of the fix. Four cards abreast at `sm`
 * squeezed a sentence into a 140px column on a phone; the row now stacks below
 * `sm`, pairs up through `lg`, and only opens out to four when there is room.
 * Arrows appear only in that last case: mid-row they would point off the end of
 * a wrapped line and lie about the order.
 */
export function StepRow({ steps }: { steps: Array<{ label: string; desc?: string }> }) {
  return (
    <ol className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      {steps.map((step, i) => (
        <li key={step.label} className="relative flex">
          <div className="flex flex-1 flex-col gap-1 rounded-lg border border-rail bg-card p-3">
            <div className="flex items-baseline gap-2">
              <span className="mono-label" aria-hidden>
                {String(i + 1).padStart(2, '0')}
              </span>
              <span className="text-sm font-medium">{step.label}</span>
            </div>
            {step.desc && (
              <p className="text-xs leading-relaxed text-muted-foreground">
                <Prose>{step.desc}</Prose>
              </p>
            )}
          </div>
          {i < steps.length - 1 && (
            <ArrowRight
              className="absolute -right-3.5 top-1/2 hidden h-4 w-4 -translate-y-1/2 text-muted-foreground lg:block"
              aria-hidden
            />
          )}
        </li>
      ))}
    </ol>
  );
}

/**
 * A framed figure with a caption — the shared shell for the diagrams and the
 * screenshots, so a drawing and a photograph of the product sit in the same
 * frame rather than each inventing one.
 */
export function Figure({ caption, children }: { caption?: string; children: ReactNode }) {
  return (
    <figure className="overflow-hidden rounded-lg border border-rail bg-card">
      {children}
      {caption && (
        <figcaption className="border-t border-rail px-3 py-2 text-xs leading-relaxed text-muted-foreground">
          {caption}
        </figcaption>
      )}
    </figure>
  );
}

/** Every tone a diagram may use. The four status hues mean what they mean everywhere else. */
export type SketchTone = 'ok' | 'retry' | 'halt' | 'idle' | 'rail';

const SketchIdContext = createContext('');

const useSketchIds = () => {
  const base = useContext(SketchIdContext);
  return {
    arrow: (tone: SketchTone) => `${base}-arrow-${tone}`,
  };
};

const TONES: SketchTone[] = ['ok', 'retry', 'halt', 'idle', 'rail'];

/**
 * The frame every docs diagram is drawn in: it owns the turbulence filter, the
 * arrowheads and the caption, so seven drawings do not each redefine them.
 *
 * `viewBox` is deliberately narrow — around 440 units — because an SVG scales
 * its text with its box. A 900-unit-wide drawing squeezed into a phone column
 * renders its 14-unit labels at six real pixels; at 440 units in a 360px column
 * the same label lands near 11px, which is the smallest worth printing. The
 * `maxWidth` cap stops the other end: without it a drawing sized for a phone
 * becomes a poster on a desktop.
 */
export function Diagram({
  caption,
  label,
  viewBox,
  maxWidth = 520,
  children,
}: {
  caption?: string;
  label: string;
  viewBox: string;
  maxWidth?: number;
  children: ReactNode;
}) {
  // useId gives every instance its own filter and marker ids: two diagrams on
  // one page sharing an id would have the second silently reuse the first.
  const base = `dg${useId().replace(/:/g, '')}`;

  return (
    <SketchIdContext.Provider value={base}>
      <Figure caption={caption}>
        <svg
          viewBox={viewBox}
          role="img"
          aria-label={label}
          className="mx-auto block w-full p-3"
          style={{ maxWidth: `${maxWidth}px` }}
        >
          <defs>
            {TONES.map((tone) => (
              <marker
                key={tone}
                id={`${base}-arrow-${tone}`}
                viewBox="0 0 10 10"
                refX={8}
                refY={5}
                markerWidth={5}
                markerHeight={5}
                orient="auto"
              >
                <path d="M0,0 L10,5 L0,10 z" fill={`hsl(var(--${tone === 'rail' ? 'muted-foreground' : tone}))`} />
              </marker>
            ))}
          </defs>
          {children}
        </svg>
      </Figure>
    </SketchIdContext.Provider>
  );
}

const stroke = (tone: SketchTone) => (tone === 'rail' ? 'hsl(var(--rail))' : `hsl(var(--${tone}))`);
const fill = (tone: SketchTone) => (tone === 'rail' ? 'hsl(var(--card))' : `hsl(var(--${tone}-soft))`);

/**
 * A node in a diagram: a panel, drawn the way every other surface in this product is drawn.
 *
 * It used to be a pill — a 2px rounded rect with one centred word in it — and a row of those
 * joined by arrows is a flowchart from a slide deck, not a drawing of this system. Three of
 * them said "Your system", "Railhook", "Endpoint", which is the sentence underneath the
 * figure with boxes around it.
 *
 * What makes a node worth drawing is what it carries, so the shape is built for content: a
 * mono `role` eyebrow above (SOURCE, ENDPOINT — the product's own vocabulary), the name, and
 * a mono `sub` line for the thing that is actually true of it — a URL, an id, an event type.
 * Left-aligned, because a label and a URL centred on each other read as a caption; stacked at
 * the same left edge they read as a record.
 *
 * `tone` stays neutral unless a real status is being shown. The colour rule in the design
 * brief is that the four status hues belong to statuses, and a diagram that paints its boxes
 * for variety teaches the reader that green means nothing.
 */
export function SketchBox({
  x,
  y,
  w,
  h = 46,
  role,
  label,
  sub,
  tone = 'rail',
  align = 'center',
}: {
  x: number;
  y: number;
  w: number;
  h?: number;
  role?: string;
  label?: string;
  sub?: string;
  tone?: SketchTone;
  align?: 'center' | 'start';
}) {
  const cx = x + w / 2;
  const cy = y + h / 2;
  const left = align === 'start';
  const tx = left ? x + 12 : cx;
  const anchor = left ? 'start' : 'middle';

  // With a role eyebrow the block is three lines and is laid out from the top; without one it
  // stays optically centred, which is what a two-line node wants.
  const labelY = role ? y + 26 : sub ? cy - 1 : cy + 4;
  const subY = role ? y + 40 : cy + 15;

  return (
    <g>
      <rect
        x={x}
        y={y}
        width={w}
        height={h}
        rx={8}
        fill={fill(tone)}
        stroke={stroke(tone)}
        strokeWidth={1.25}
      />
      {role && (
        <text
          x={tx}
          y={y + 13}
          textAnchor={anchor}
          fontSize={8.5}
          letterSpacing="0.09em"
          className="font-mono"
          fill="hsl(var(--muted-foreground))"
        >
          {role.toUpperCase()}
        </text>
      )}
      {label && (
        <text x={tx} y={labelY} textAnchor={anchor} fontSize={13.5} fill="hsl(var(--foreground))">
          {label}
        </text>
      )}
      {sub && (
        <text x={tx} y={subY} textAnchor={anchor} fontSize={10.5} className="font-mono" fill="hsl(var(--muted-foreground))">
          {sub}
        </text>
      )}
    </g>
  );
}

/** A connector. `dashed` is reserved for a transition where nothing was sent. */
export function SketchEdge({
  d,
  tone = 'rail',
  dashed = false,
}: {
  d: string;
  tone?: SketchTone;
  dashed?: boolean;
}) {
  const ids = useSketchIds();
  return (
    <path
      d={d}
      fill="none"
      stroke={tone === 'rail' ? 'hsl(var(--muted-foreground))' : stroke(tone)}
      strokeWidth={1.25}
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeDasharray={dashed ? '4 4' : undefined}
      markerEnd={`url(#${ids.arrow(tone)})`}
      opacity={tone === 'rail' ? 0.55 : 1}
    />
  );
}

/**
 * A mono token sitting on a connector: the thing that travels.
 *
 * This is the element the old diagrams had no answer for. "Railhook → Endpoint" with the word
 * "signs" floating underneath tells the reader a verb; `X-Signature: t=…,v1=…` riding the
 * arrow tells them what to look for in their own logs. The plate behind it is the page
 * background rather than a fill, so the line appears to pass behind the text instead of
 * through it.
 */
export function SketchChip({
  x,
  y,
  children,
  tone = 'rail',
  leaderFrom,
}: {
  x: number;
  y: number;
  children: string;
  tone?: SketchTone;
  /** y of the connector this annotates: a hairline is drawn from there down to the chip. */
  leaderFrom?: number;
}) {
  const w = children.length * 5.4 + 14;
  return (
    <g>
      {leaderFrom !== undefined && (
        /* Without it the chip floats under the row and reads as a caption for the whole
           diagram rather than for the one arrow it belongs to. */
        <line
          x1={x}
          y1={leaderFrom}
          x2={x}
          y2={y - 9}
          stroke="hsl(var(--rail))"
          strokeWidth={1}
        />
      )}
      <rect
        x={x - w / 2}
        y={y - 9}
        width={w}
        height={18}
        rx={4}
        fill="hsl(var(--card))"
        stroke={tone === 'rail' ? 'hsl(var(--rail))' : stroke(tone)}
        strokeWidth={1}
      />
      <text
        x={x}
        y={y + 3.5}
        textAnchor="middle"
        fontSize={10}
        className="font-mono"
        fill={tone === 'rail' ? 'hsl(var(--foreground))' : stroke(tone)}
      >
        {children}
      </text>
    </g>
  );
}

/**
 * The attempt rail, inside a diagram.
 *
 * The design brief calls this the product's signature: a hairline with ticks placed on a log
 * scale of their delay, because the ladder is 1m → 5m → 15m → 1h → 6h → 24h and even spacing
 * would draw a schedule the product does not run. `AttemptRail` is the React component for
 * it; this is the same geometry as SVG primitives so a drawing can use it inline.
 *
 * `attempts` is how many rungs the ladder has — seven outgoing, five incoming — and the ticks
 * past that count are drawn faintly, so the difference between the two directions is
 * something you see rather than read.
 */
const LADDER_MINUTES = [0, 1, 5, 15, 60, 360, 1440];

export function SketchRail({
  x,
  y,
  w,
  attempts = LADDER_MINUTES.length,
}: {
  x: number;
  y: number;
  w: number;
  attempts?: number;
}) {
  const span = Math.log1p(1440);
  const at = (minutes: number) => x + (Math.log1p(minutes) / span) * w;

  return (
    <g>
      <line x1={x} y1={y} x2={x + w} y2={y} stroke="hsl(var(--rail))" strokeWidth={1.25} />
      {LADDER_MINUTES.map((m, i) => (
        <circle
          key={m}
          cx={at(m)}
          cy={y}
          r={i < attempts ? 3 : 2}
          fill={i < attempts ? 'hsl(var(--primary))' : 'hsl(var(--rail))'}
          opacity={i < attempts ? 1 : 0.7}
        />
      ))}
    </g>
  );
}

/** Diagram text that is not a box label: an edge annotation, a heading, a footnote. */
/**
 * Diagram text that is not a box label: an edge annotation, a heading, a footnote.
 *
 * Backticks are stripped rather than rendered. Docs prose marks identifiers with them for
 * `Prose` to turn into chips, and several of those same strings are also used as edge labels
 * — where there is no chip to make, so the reader saw a literal `` `2xx` `` painted into the
 * drawing.
 */
export function SketchText({
  x,
  y,
  anchor = 'middle',
  size = 14,
  mono = false,
  tone,
  children,
}: {
  x: number;
  y: number;
  anchor?: 'start' | 'middle' | 'end';
  size?: number;
  mono?: boolean;
  tone?: SketchTone;
  children: string;
}) {
  return (
    <text
      x={x}
      y={y}
      textAnchor={anchor}
      fontSize={size}
      className={mono ? 'font-mono' : undefined}
      fill={tone && tone !== 'rail' ? `hsl(var(--${tone}))` : 'hsl(var(--muted-foreground))'}
    >
      {children.replace(/`/g, '')}
    </text>
  );
}
