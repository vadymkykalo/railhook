import type { ReactNode } from 'react';
import { cn } from '../lib/utils';

/** Title is optional: on a tabbed page the header bar and tab already name the view. */
export default function PageHeader({
  eyebrow, title, description, actions, className,
}: {
  eyebrow?: ReactNode;
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('flex flex-wrap items-start justify-between gap-4 pb-5', className)}>
      <div className="min-w-0">
        {eyebrow && <div className={cn('mono-label', title ? 'mb-1.5' : 'mb-1')}>{eyebrow}</div>}
        {title && <h2 className="text-title">{title}</h2>}
        {description && (
          <p className={cn('max-w-2xl text-sm text-muted-foreground', title && 'mt-1')}>{description}</p>
        )}
      </div>
      {actions && <div className="flex flex-shrink-0 flex-wrap items-center gap-2 max-sm:w-full">{actions}</div>}
    </div>
  );
}
