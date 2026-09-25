import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '../../lib/utils';
import { SectionLabel } from './primitives';

export const IN = 'px-4 sm:px-10 xl:px-[106px]';

export function Rule({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cn('lp-rule', className)} />;
}

export function LabelBand({ id, soft = false, children }: { id?: string; soft?: boolean; children: ReactNode }) {
  return (
    <div id={id} className={cn('flex h-[88px] scroll-mt-14 items-center px-4 sm:px-7', soft && 'bg-muted')}>
      <SectionLabel>{children}</SectionLabel>
    </div>
  );
}

export function Hl({ children }: { children?: ReactNode }) {
  return <span className="mark-hl">{children}</span>;
}

export const RICH = {
  br: <br />,
  hl: <Hl />,
  c: <code className="font-mono text-[0.9em]" />,
};

export function ArrowLink({
  href,
  to,
  dark = false,
  children,
  className,
}: {
  href?: string;
  to?: string;
  dark?: boolean;
  children: ReactNode;
  className?: string;
}) {
  const classes = cn('lp-link', dark && 'lp-link--dark', className);
  const content = (
    <>
      <span aria-hidden="true">→</span>
      <span className="lp-link-text">{children}</span>
    </>
  );
  return to ? (
    <Link to={to} className={classes}>
      {content}
    </Link>
  ) : (
    <a href={href} className={classes}>
      {content}
    </a>
  );
}

export function Chevron() {
  return (
    <span aria-hidden="true" className="-mr-0.5 text-[17px] leading-none">
      ›
    </span>
  );
}

export function Node({ hl = false, icon = true, children }: { hl?: boolean; icon?: boolean; children: ReactNode }) {
  return (
    <span className={cn('lp-node', hl && 'lp-node--hl')}>
      {icon && !hl && <span aria-hidden="true" className="lp-node-ic" />}
      {children}
    </span>
  );
}

export function Wire({ mark, markClassName, children }: { mark: ReactNode; markClassName?: string; children: ReactNode }) {
  return (
    <div className="lp-wire">
      <b className={markClassName}>{mark}</b>
      <span>{children}</span>
    </div>
  );
}
