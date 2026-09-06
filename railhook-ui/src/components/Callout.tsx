import { type ReactNode } from 'react';
import { AlertTriangle, Info } from 'lucide-react';
import { cn } from '../lib/utils';

interface CalloutProps {
  children: ReactNode;
  /** A warning is the default; info is for a note that costs nothing to ignore. */
  tone?: 'warning' | 'info';
  className?: string;
}

/**
 * A boxed aside — what this will cost, or what to know before doing it.
 *
 * <p>Eleven places drew this by hand with the same four utility classes, which is how three of
 * them ended up a padding step off from the rest.
 */
export default function Callout({ children, tone = 'warning', className }: CalloutProps) {
  const Icon = tone === 'warning' ? AlertTriangle : Info;
  const palette = tone === 'warning'
    ? 'border-retry/30 bg-retry-soft text-retry'
    : 'border-rail bg-secondary/50 text-muted-foreground';

  return (
    <div className={cn('flex items-start gap-2 rounded-lg border p-3', palette, className)}>
      <Icon className="mt-0.5 h-4 w-4 flex-shrink-0" aria-hidden />
      <p className="text-sm">{children}</p>
    </div>
  );
}
