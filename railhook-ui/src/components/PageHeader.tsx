import type { ReactNode } from 'react';
import { cn } from '../lib/utils';
import PageGuide, { type PageGuideProps } from './PageGuide';

/**
 * One page header for every admin page, so the title, the count and the primary
 * action always land in the same place. The mono eyebrow carries the scope
 * (which project, which direction) — machine facts belong in the machine voice.
 *
 * <p>The title is the page's one `h1`, and on a page reached from a tab it reads
 * exactly what the tab reads: a newcomer who clicked "Failed deliveries" should
 * land on a page that says "Failed deliveries", not on one that has renamed itself
 * on the way. The layout's header bar names the section in plain text, so the
 * page, not the chrome, owns the heading.
 */
export default function PageHeader({
  eyebrow, title, description, actions, guide, className,
}: {
  eyebrow?: ReactNode;
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
  guide?: PageGuideProps;
  className?: string;
}) {
  const header = (
    <div className={cn('flex flex-wrap items-start justify-between gap-4 pb-5', className)}>
      <div className="min-w-0">
        {eyebrow && <div className={cn('mono-label', title ? 'mb-1.5' : 'mb-1')}>{eyebrow}</div>}
        {title && <h1 className="text-title">{title}</h1>}
        {description && (
          <p className={cn('max-w-2xl text-sm text-muted-foreground', title && 'mt-1')}>{description}</p>
        )}
      </div>
      {actions && <div className="flex flex-shrink-0 flex-wrap items-center gap-2 max-sm:w-full">{actions}</div>}
    </div>
  );

  if (!guide) return header;
  return (
    <>
      {header}
      <PageGuide {...guide} />
    </>
  );
}
