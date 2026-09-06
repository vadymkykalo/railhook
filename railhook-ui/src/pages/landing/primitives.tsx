import type { ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../lib/utils';
import SyntaxHighlight, { normalizeLanguage } from '../../components/SyntaxHighlight';

/**
 * The landing page's shared furniture.
 *
 * Two rules are enforced here rather than repeated in every section: section
 * headers are left-aligned (the old page centred seventeen identical ones), and
 * the divider between sections is the attempt rail — a hairline with ticks at
 * log-spaced positions — not a decorative gradient line.
 */

/** Tick positions as fractions of the rail, on a log scale of 1m…24h. */
const LADDER_MINUTES = [0, 1, 5, 15, 60, 360, 1440];
const LADDER_SPAN = 1440;

function logPosition(minutes: number): number {
  if (minutes <= 0) return 0;
  return Math.log1p(minutes) / Math.log1p(LADDER_SPAN);
}

/**
 * The structural divider: a rail carrying the ladder's own tick spacing.
 * Decorative, so it is hidden from the accessibility tree.
 */
export function RailRule({ className }: { className?: string }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 1000 8"
      preserveAspectRatio="none"
      className={cn('h-2 w-full', className)}
    >
      <line x1="0" y1="4" x2="1000" y2="4" stroke="hsl(var(--rail))" strokeWidth="1" vectorEffect="non-scaling-stroke" />
      {LADDER_MINUTES.map((m) => (
        <line
          key={m}
          x1={logPosition(m) * 1000}
          y1="0"
          x2={logPosition(m) * 1000}
          y2="8"
          stroke="hsl(var(--rail))"
          strokeWidth="1"
          vectorEffect="non-scaling-stroke"
        />
      ))}
    </svg>
  );
}

/**
 * The landing's card surface, in one place because eight sections were each
 * spelling it out and four of them had drifted.
 *
 * `interactive` is the hover state: the rail warms to brand teal and the panel
 * rises one step off the paper. It is opt-in rather than universal so the lift
 * still means something — a panel that merely holds a fact does not move, and a
 * panel you are meant to read as an option does.
 */
export function panel(interactive = false): string {
  return cn(
    'rounded-xl border border-rail bg-card',
    interactive
      && 'transition-[border-color,box-shadow,transform] duration-200 hover:-translate-y-0.5 '
       + 'hover:border-primary/50 hover:shadow-elevated motion-reduce:hover:translate-y-0',
  );
}

export function Section({
  id,
  children,
  className,
  ruled = true,
}: {
  id?: string;
  children: ReactNode;
  className?: string;
  ruled?: boolean;
}) {
  return (
    <section id={id} className={cn('relative', className)}>
      {ruled && <RailRule />}
      <div className="mx-auto max-w-6xl px-5 py-12 sm:px-6 lg:py-14">{children}</div>
    </section>
  );
}

export function SectionHeader({
  eyebrow,
  title,
  body,
  className,
  aside,
}: {
  eyebrow: string;
  title: string;
  body?: string;
  className?: string;
  aside?: ReactNode;
}) {
  const heading = (
    <div>
      <p className="mono-label">{eyebrow}</p>
      <h2 className="mt-3 font-display text-3xl leading-[1.1] tracking-tight text-foreground sm:text-headline">
        {title}
      </h2>
    </div>
  );

  /* With an aside, the deck stays under the headline and the aside takes the
     right column. Without one, the deck *is* the right column: stacking it
     under the headline left the right half of every section header empty and
     made the block twice as tall, which is what read as filler. */
  if (aside) {
    return (
      <div className={cn('flex flex-col gap-6 md:flex-row md:items-end md:justify-between', className)}>
        <div className="max-w-2xl">
          {heading}
          {body && <p className="mt-4 text-body-lg text-muted-foreground">{body}</p>}
        </div>
        <div className="shrink-0">{aside}</div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'grid gap-5 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)] lg:items-end lg:gap-14',
        className,
      )}
    >
      <div className="max-w-2xl">{heading}</div>
      {body && <p className="max-w-xl text-body-lg text-muted-foreground lg:pb-1">{body}</p>}
    </div>
  );
}

/**
 * A service mark from public/logos.
 *
 * Flattened to one treatment — grey on paper, inverted on ink — because the
 * files are a mix of brand-coloured and monochrome SVGs, and a strip where half
 * the marks shout their own colour and half do not is louder than anything on
 * the page it sits under. A logo strip is evidence, not decoration.
 *
 * Tried and reverted: masking each glyph and painting it in the service's brand
 * hex. A mask keeps only the silhouette, so any mark carrying internal detail —
 * the Postgres elephant, the Kafka glyph — collapsed into a blob, and the row
 * of brand colours fought the palette instead of sitting inside it.
 */
export function LogoMark({ src, name, className }: { src: string; name: string; className?: string }) {
  return (
    <img
      src={src}
      alt={name}
      loading="lazy"
      className={cn(
        'h-6 w-6 shrink-0 opacity-70 grayscale transition-opacity duration-250 dark:opacity-80 dark:invert',
        className,
      )}
    />
  );
}

/**
 * A code sample sits on the ink surface, not on paper: a dark field is where
 * machine output lives in this design, and it is the same surface the auth
 * pages use. The caption underneath stays in the paper voice so the boundary
 * between what the machine said and what we are telling you stays visible.
 *
 * `wrap` breaks long shell lines rather than clipping a command the reader is
 * meant to copy.
 */
export function CodeBlock({
  code,
  label,
  className,
  wrap = false,
}: {
  code: string;
  label?: string;
  className?: string;
  wrap?: boolean;
}) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className={cn('surface-ink min-w-0 max-w-full overflow-hidden rounded-lg border border-rail', className)}>
      <div className="flex items-center justify-between gap-3 border-b border-rail px-3 py-2">
        <span className="truncate font-mono text-[11px] tracking-tight text-muted-foreground">{label}</span>
        <button
          type="button"
          onClick={onCopy}
          aria-label={t('landing.quickstart.copyAria')}
          className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 font-mono text-[11px] text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
        >
          {copied ? <Check className="h-3 w-3 text-ok" aria-hidden="true" /> : <Copy className="h-3 w-3" aria-hidden="true" />}
          {copied ? t('landing.quickstart.copied') : t('landing.quickstart.copy')}
        </button>
      </div>
      {/* Same highlighter the docs use, so a sample does not read one way on
          the marketing page and another way in the documentation it links to. */}
      <pre
        className={cn(
          'px-4 py-3.5 font-mono text-[12.5px] leading-relaxed text-foreground',
          wrap ? 'whitespace-pre-wrap break-words' : 'overflow-x-auto',
        )}
      >
        <code>
          <SyntaxHighlight code={code} language={normalizeLanguage(label)} />
        </code>
      </pre>
    </div>
  );
}


function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * A block that fades and lifts into place the first time it is scrolled to, and
 * then never again — re-triggering on the way back up is what makes a reveal
 * feel like a trick rather than like the page arriving. Under
 * prefers-reduced-motion nothing is ever hidden in the first place.
 */
export function Reveal({
  children,
  delay = 0,
  className,
}: {
  children: ReactNode;
  delay?: number;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [shown, setShown] = useState(() => prefersReducedMotion());

  useEffect(() => {
    if (shown) return;
    const node = ref.current;
    if (!node || typeof IntersectionObserver === 'undefined') {
      setShown(true);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setShown(true);
          observer.disconnect();
        }
      },
      { threshold: 0.08, rootMargin: '0px 0px -8% 0px' },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [shown]);

  return (
    <div
      ref={ref}
      className={cn(
        'transition-[opacity,transform] duration-500 ease-out motion-reduce:transition-none',
        shown ? 'translate-y-0 opacity-100' : 'translate-y-3 opacity-0',
        className,
      )}
      style={shown && delay ? { transitionDelay: `${delay}ms` } : undefined}
    >
      {children}
    </div>
  );
}
