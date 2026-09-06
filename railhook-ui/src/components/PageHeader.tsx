import type { ReactNode } from 'react';
import { cn } from '../lib/utils';

/**
 * One page header for every admin page, so the title, the count and the primary
 * action always land in the same place. The mono eyebrow carries the scope
 * (which project, which direction) — machine facts belong in the machine voice.
 *
 * <p>`title` is optional, and leaving it out is the right call more often than
 * it looks. The layout's header bar already names the section and the tab strip
 * already names the view, so on a tabbed page a title here is the third time in
 * 180px that the screen says the same word — on Deliveries it read
 * "Deliveries / All deliveries / All deliveries" before the first row of data.
 * Give it a title when it names something the tab does not (Connections' tab
 * says "Connections", its header says "Connection Map"); otherwise let the
 * description have the space.
 */
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
      {actions && <div className="flex flex-shrink-0 items-center gap-2">{actions}</div>}
    </div>
  );
}
