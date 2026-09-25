import { cn } from '../../lib/utils';

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
