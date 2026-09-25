import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowUpRight } from 'lucide-react';
import { cn } from '../../lib/utils';
import Sparkline from '../Sparkline';
import { SERIES } from './chartTheme';

interface StatTileProps {
  label: string;
  value: string;
  hint?: ReactNode;
  badge?: ReactNode;
  spark?: number[];
  to?: string;
  className?: string;
}

/** Proportional figures, not tabular-nums: equal-width digits look loose at tile size. */
export default function StatTile({ label, value, hint, badge, spark, to, className }: StatTileProps) {
  const body = (
    <>
      <div className="flex items-start justify-between gap-2">
        <span className="mono-label">{label}</span>
        {badge}
        {to && !badge && (
          <ArrowUpRight className="h-3.5 w-3.5 flex-shrink-0 text-muted-foreground/60 transition-colors group-hover:text-foreground" aria-hidden />
        )}
      </div>
      <div className="mt-2 flex items-end justify-between gap-3">
        <div className="min-w-0">
          <p className="text-2xl font-medium leading-none tracking-tight">{value}</p>
          {hint && <p className="mt-1.5 text-xs text-muted-foreground">{hint}</p>}
        </div>
        {spark && spark.length >= 2 && (
          <Sparkline data={spark} width={72} height={28} strokeColor={SERIES.brand} fillColor={SERIES.brand} />
        )}
      </div>
    </>
  );

  const shell = 'block border border-rail bg-card p-4 shadow-card';

  if (to) {
    return (
      <Link to={to} className={cn(shell, 'group transition-shadow hover:shadow-card-hover', className)}>
        {body}
      </Link>
    );
  }
  return <div className={cn(shell, className)}>{body}</div>;
}
