import { type ReactNode } from 'react';

/** Mirrors the real layout so nothing shifts when data lands. */
interface PageSkeletonProps {
  maxWidth?: string;
  header?: boolean;
  children?: ReactNode;
}

export default function PageSkeleton({ maxWidth = 'max-w-6xl', header = true, children }: PageSkeletonProps) {
  return (
    <div className={`p-4 lg:p-6 ${maxWidth} mx-auto space-y-5`} aria-busy="true">
      {header && (
        <div className="flex items-start justify-between pb-1">
          <div className="space-y-2">
            <div className="h-3 w-20 animate-pulse rounded bg-muted" />
            <div className="h-6 w-40 animate-pulse bg-muted" />
            <div className="h-3.5 w-56 animate-pulse rounded bg-muted" />
          </div>
          <div className="h-9 w-32 animate-pulse bg-muted" />
        </div>
      )}
      {children ?? <div className="h-[400px] animate-pulse border border-rail bg-muted" />}
    </div>
  );
}

export function SkeletonCards({ count = 3, height = 'h-32', cols = 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3' }: { count?: number; height?: string; cols?: string }) {
  return (
    <div className={`grid gap-4 ${cols}`}>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className={`${height} animate-pulse border border-rail bg-muted`} />
      ))}
    </div>
  );
}

export function SkeletonRows({ count = 3, height = 'h-16' }: { count?: number; height?: string }) {
  return (
    <div className="space-y-2.5">
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className={`${height} animate-pulse border border-rail bg-muted`} />
      ))}
    </div>
  );
}

export function SkeletonTable({ rows = 5 }: { rows?: number }) {
  return (
    <div className="space-y-3 p-4">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-4">
          <div className="h-3.5 w-24 animate-pulse rounded bg-muted" />
          <div className="h-3.5 w-20 animate-pulse rounded bg-muted" />
          <div className="h-3.5 flex-1 animate-pulse rounded bg-muted" />
          <div className="h-3.5 w-16 animate-pulse rounded bg-muted" />
        </div>
      ))}
    </div>
  );
}
