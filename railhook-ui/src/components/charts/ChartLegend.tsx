import { cn } from '../../lib/utils';

export interface LegendItem {
  label: string;
  color: string;
  shape?: 'line' | 'rect';
  opacity?: number;
}

export default function ChartLegend({ items, className }: { items: LegendItem[]; className?: string }) {
  if (items.length < 2) return null;
  return (
    <ul className={cn('flex flex-wrap items-center gap-x-4 gap-y-1', className)}>
      {items.map((item) => (
        <li key={item.label} className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <span
            aria-hidden
            className={cn('flex-shrink-0', item.shape === 'line' ? 'h-0.5 w-3.5 rounded-full' : 'h-2.5 w-2.5')}
            style={{ backgroundColor: item.color, opacity: item.opacity ?? 1 }}
          />
          {item.label}
        </li>
      ))}
    </ul>
  );
}
