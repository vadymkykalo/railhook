import { useState } from 'react';
import { ArrowUp, ArrowDown, ArrowUpDown } from 'lucide-react';
import { cn } from '../../lib/utils';
import { TableHead } from './table';

export type SortDirection = 'asc' | 'desc';

export interface SortState {
  field: string;
  direction: SortDirection;
}

interface SortableTableHeadProps {
  field: string;
  sort: SortState;
  onSort: (field: string) => void;
  children: React.ReactNode;
  className?: string;
}

/* Composes TableHead rather than rendering its own <th>. It used to declare
   `h-12 px-4 font-medium text-xs`, which is what shadcn shipped before the
   header row was restyled — so any table mixing a sortable column with a plain
   one showed a 12px height jog and two typefaces in the same row. Everything
   here is now only what sorting adds: the pointer affordance and the arrow. */
export function SortableTableHead({ field, sort, onSort, children, className }: SortableTableHeadProps) {
  const isActive = sort.field === field;

  return (
    <TableHead
      className={cn(
        'cursor-pointer select-none transition-colors hover:text-foreground',
        isActive && 'text-foreground',
        className
      )}
      onClick={() => onSort(field)}
    >
      <div className="flex items-center gap-1">
        {children}
        {isActive ? (
          sort.direction === 'asc' ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />
        ) : (
          <ArrowUpDown className="h-3 w-3 opacity-30" />
        )}
      </div>
    </TableHead>
  );
}

export function useSort(defaultField = 'createdAt', defaultDir: SortDirection = 'desc') {
  const [sort, setSort] = useState<SortState>({ field: defaultField, direction: defaultDir });

  const toggle = (field: string) => {
    setSort(prev => ({
      field,
      direction: prev.field === field && prev.direction === 'desc' ? 'asc' : 'desc',
    }));
  };

  const param = `${sort.field},${sort.direction}`;

  return { sort, toggle, param };
}
