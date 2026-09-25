import { type LucideIcon, BookOpen, AlertTriangle, RefreshCw } from 'lucide-react';
import { type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docsUrl } from '../lib/docsUrl';
import { resolveErrorMessage } from '../lib/toast';
import { Button } from './ui/button';
import { cn } from '../lib/utils';

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  /** ReactNode: some descriptions name the record in <strong>. */
  description?: ReactNode;
  action?: ReactNode;
  docsLink?: string;
  className?: string;
}

export default function EmptyState({ icon: Icon, title, description, action, docsLink, className }: EmptyStateProps) {
  const { t, i18n } = useTranslation();
  return (
    <div className={cn('flex flex-col items-center justify-center border border-dashed border-rail py-16', className)}>
      <div className="mb-5 flex h-11 w-11 items-center justify-center border border-rail bg-card">
        <Icon className="h-5 w-5 text-muted-foreground" />
      </div>
      <h3 className="mb-1.5 text-[15px] font-medium">{title}</h3>
      {description && (
        <p className="mb-5 max-w-sm text-center text-sm text-muted-foreground">{description}</p>
      )}
      {action && <div className="mb-3">{action}</div>}
      {docsLink && (
        <a href={docsUrl(i18n.language, docsLink)} className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors">
          <BookOpen className="h-3.5 w-3.5" />
          {t('common.learnMore')}
        </a>
      )}
    </div>
  );
}

interface ErrorStateProps {
  error?: unknown;
  fallbackKey?: string;
  description?: string;
  title?: string;
  onRetry?: () => void;
  retrying?: boolean;
  className?: string;
  testId?: string;
}

/** Never EmptyState for a failed request, or a down backend looks like an empty account. */
export function ErrorState({
  error,
  fallbackKey = 'common.error',
  description,
  title,
  onRetry,
  retrying = false,
  className,
  testId = 'error-state',
}: ErrorStateProps) {
  const { t } = useTranslation();
  const resolvedDescription = description ?? (error !== undefined ? resolveErrorMessage(error, fallbackKey) : t(fallbackKey));

  return (
    <div
      data-testid={testId}
      role="alert"
      className={className ?? 'flex flex-col items-center justify-center border border-dashed border-halt/30 py-16'}
    >
      <div className="mb-5 flex h-11 w-11 items-center justify-center border border-halt/30 bg-halt-soft">
        <AlertTriangle className="h-5 w-5 text-halt" />
      </div>
      <h3 className="mb-1.5 text-[15px] font-medium">{title ?? t('common.loadErrorTitle')}</h3>
      <p className="mb-5 max-w-sm text-center text-sm text-muted-foreground">{resolvedDescription}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry} disabled={retrying}>
          <RefreshCw className={`h-3.5 w-3.5 ${retrying ? 'animate-spin' : ''}`} />
          {retrying ? t('common.retrying') : t('common.retry')}
        </Button>
      )}
    </div>
  );
}
