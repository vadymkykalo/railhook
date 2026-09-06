import { useTranslation } from 'react-i18next';
import { Button } from './button';
import { Select } from './select';

interface TablePaginationProps {
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  pageSizeOptions?: number[];
}

export function TablePagination({
  page,
  pageSize,
  totalElements,
  totalPages,
  onPageChange,
  onPageSizeChange,
  pageSizeOptions = [20, 50, 100],
}: TablePaginationProps) {
  const { t } = useTranslation();

  if (totalElements === 0) return null;

  return (
    <div className="flex items-center justify-between mt-4">
      <div className="flex items-center gap-3">
        <p className="text-xs text-muted-foreground">
          {t('common.showing', {
            from: page * pageSize + 1,
            to: Math.min((page + 1) * pageSize, totalElements),
            total: totalElements,
          })}
        </p>
        <Select
          className="w-20 h-8 text-xs"
          value={pageSize.toString()}
          onChange={(e) => {
            onPageSizeChange(Number(e.target.value));
            onPageChange(0);
          }}
          aria-label={t('common.pageSize')}
        >
          {pageSizeOptions.map((size) => (
            <option key={size} value={size}>
              {size}
            </option>
          ))}
        </Select>
      </div>
      {totalPages > 1 && (
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => onPageChange(Math.max(0, page - 1))}
            disabled={page === 0}
          >
            {t('common.previous')}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => onPageChange(Math.min(totalPages - 1, page + 1))}
            disabled={page >= totalPages - 1}
          >
            {t('common.next')}
          </Button>
        </div>
      )}
    </div>
  );
}
