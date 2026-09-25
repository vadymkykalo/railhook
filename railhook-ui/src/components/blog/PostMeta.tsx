import { cn } from '../../lib/utils';

/**
 * The byline and the tags, shared by the blog index and the article so the two never drift.
 *
 * Set a size up from the app's `mono-label` (11px): that one labels table columns, where a reader
 * glances, while a byline is read — author, date, minutes — and at 11px uppercase it was the
 * smallest text on a page otherwise set at 17px.
 */
export const BYLINE =
  'flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-[13px] font-medium uppercase tracking-[0.06em] text-muted-foreground';

export function TagList({ tags, className }: { tags: string[]; className?: string }) {
  if (tags.length === 0) return null;
  return (
    <ul className={cn('flex flex-wrap gap-2', className)}>
      {tags.map((tag) => (
        <li key={tag} className="bg-accent px-3 py-1 font-mono text-[13px] leading-5 tracking-[0.02em] text-accent-foreground">
          {tag}
        </li>
      ))}
    </ul>
  );
}
