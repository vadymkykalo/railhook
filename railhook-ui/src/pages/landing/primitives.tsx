import type { ComponentType, ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import { cn } from '../../lib/utils';

export const WRAP = 'mx-auto w-full max-w-[1336px] px-4 sm:px-10 xl:px-[106px]';

export function panel(interactive = false): string {
  return cn(
    'border border-rail bg-card',
    interactive && 'transition-colors duration-200 hover:border-foreground',
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
    <section id={id} className={cn('relative', ruled && 'border-t border-rail', className)}>
      <div className={cn(WRAP, 'py-12 lg:py-14')}>{children}</div>
    </section>
  );
}

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
      className={cn('scroll-mt-16 py-16 sm:py-[88px]', muted && 'border-y border-rail bg-muted')}
    >
      <div className={WRAP}>{children}</div>
    </section>
  );
}

export function SectionLabel({ children, className, as: Tag = 'p' }: { children: ReactNode; className?: string; as?: 'p' | 'span' | 'h2' }) {
  return (
    <Tag className={cn('flex items-center gap-3 font-mono text-[12px] font-normal uppercase leading-none tracking-[0.06em] text-[#333] dark:text-muted-foreground', className)}>
      <span aria-hidden="true" className="h-2 w-2 flex-none bg-foreground" />
      {children}
    </Tag>
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
    <div className={cn('mb-10 grid max-w-2xl gap-3 max-sm:mb-8', className)}>
      <h2
        id={id}
        className="text-[1.75rem] font-normal leading-[1.16] tracking-[-0.02em] text-foreground [text-wrap:balance] sm:text-[2.5rem]"
      >
        {title}
      </h2>
      {lead && <p className="text-[1.0625rem] text-[#333] dark:text-muted-foreground">{lead}</p>}
    </div>
  );
}

export function PageIntro({
  eyebrow,
  title,
  lead,
  children,
}: {
  eyebrow?: string;
  title: string;
  lead: string;
  children?: ReactNode;
}) {
  return (
    <section className="pb-4 pt-14 sm:pt-[88px]">
      <div className={WRAP}>
        {eyebrow && <SectionLabel className="mb-6">{eyebrow}</SectionLabel>}
        <h1 className="max-w-4xl text-[2.375rem] font-normal leading-[1.16] tracking-[-0.03em] text-foreground [text-wrap:balance] sm:text-[3.5rem]">
          {title}
        </h1>
        <p className="mt-5 max-w-2xl text-[1.0625rem] text-[#333] dark:text-muted-foreground">{lead}</p>
        {children && <div className="mt-7">{children}</div>}
      </div>
    </section>
  );
}

export function FactCard({
  icon: Icon,
  title,
  children,
}: {
  icon: ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>;
  title: string;
  children: ReactNode;
}) {
  return (
    <div className={cn('flex h-full flex-col p-6 sm:p-8', panel())}>
      <h3 className="flex items-center gap-2 font-mono text-[11.5px] font-normal uppercase leading-snug tracking-[0.06em] text-[#333] dark:text-muted-foreground">
        <Icon className="h-4 w-4 flex-none text-foreground" aria-hidden="true" />
        {title}
      </h3>
      <p className="mt-3.5 text-[16px] leading-[1.45] text-[#222] dark:text-foreground">{children}</p>
    </div>
  );
}

export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

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
