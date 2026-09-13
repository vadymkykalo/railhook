import type { ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import { cn } from '../../lib/utils';

/**
 * The public pages' shared furniture: the landing's bands and section headings, and the panel,
 * section and reveal the contact page is built from.
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
 * A card surface. `interactive` adds the hover state — the rail warms to the accent and the
 * panel rises one step — and is opt-in so the lift still means "this is an option".
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

/** The landing's content column. */
export const WRAP = 'mx-auto w-full max-w-[1080px] px-5 sm:px-7';

/**
 * One landing section: a full-width band, optionally on the muted surface, with the content
 * column inside. `scroll-mt` keeps an anchored band clear of the sticky header.
 */
export function Band({
  id,
  muted = false,
  labelledBy,
  children,
}: {
  id?: string;
  muted?: boolean;
  labelledBy?: string;
  children: ReactNode;
}) {
  return (
    <section
      id={id}
      aria-labelledby={labelledBy}
      className={cn('scroll-mt-16 py-16 sm:py-[84px]', muted && 'border-y border-rail bg-muted')}
    >
      <div className={WRAP}>{children}</div>
    </section>
  );
}

export function SectionHeading({
  id,
  title,
  lead,
  className,
}: {
  id?: string;
  title: string;
  lead?: string;
  className?: string;
}) {
  return (
    <div className={cn('mb-10 grid max-w-2xl gap-2.5 max-sm:mb-8', className)}>
      <h2
        id={id}
        className="font-display text-[1.75rem] font-bold leading-[1.1] tracking-[-0.03em] text-foreground [text-wrap:balance] sm:text-[clamp(1.55rem,3vw,2.3rem)] sm:leading-[1.12] sm:tracking-[-0.025em]"
      >
        {title}
      </h2>
      {lead && <p className="text-[1.05rem] text-muted-foreground">{lead}</p>}
    </div>
  );
}

export function prefersReducedMotion(): boolean {
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
