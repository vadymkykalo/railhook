import { type LucideIcon, BookOpen, AlertTriangle, RefreshCw } from 'lucide-react';
import { type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { resolveErrorMessage } from '../lib/toast';
import { Button } from './ui/button';

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  /** ReactNode, not string: some descriptions name the record in <strong>. */
  description?: ReactNode;
  action?: ReactNode;
  docsLink?: string;
  className?: string;
}

export default function EmptyState({ icon: Icon, title, description, action, docsLink, className }: EmptyStateProps) {
  const { t } = useTranslation();
  return (
    <div className={className ?? 'flex flex-col items-center justify-center rounded-lg border border-dashed border-rail py-16'}>
      <div className="mb-5 flex h-11 w-11 items-center justify-center rounded-lg border border-rail bg-card">
        <Icon className="h-5 w-5 text-muted-foreground" />
      </div>
      <h3 className="mb-1.5 text-[15px] font-medium">{title}</h3>
      {description && (
        <p className="mb-5 max-w-sm text-center text-sm text-muted-foreground">{description}</p>
      )}
      {action && <div className="mb-3">{action}</div>}
      {docsLink && (
        <Link to={docsLink} className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors">
          <BookOpen className="h-3.5 w-3.5" />
          {t('common.learnMore')}
        </Link>
      )}
    </div>
  );
}

interface ErrorStateProps {
  /** The caught error, if any — used to derive a specific, human-readable cause. */
  error?: unknown;
  /** i18n key used as a last-resort message when the error carries no server message. */
  fallbackKey?: string;
  /** Overrides the derived message entirely (skips resolveErrorMessage). */
  description?: string;
  title?: string;
  /** Refetch/retry callback. Renders a "Retry" button when provided. */
  onRetry?: () => void;
  /** True while a retry is in flight — disables the button and shows a spinner. */
  retrying?: boolean;
  className?: string;
  testId?: string;
}

/**
 * The "something is wrong, here's why, here's how to recover" state.
 * Distinct from EmptyState (which means "this loaded fine and there's just
 * nothing here yet") — never render EmptyState when a request actually failed,
 * or a down backend looks identical to an empty account.
 */
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
      className={className ?? 'flex flex-col items-center justify-center rounded-lg border border-dashed border-halt/30 py-16'}
    >
      <div className="mb-5 flex h-11 w-11 items-center justify-center rounded-lg border border-halt/30 bg-halt-soft">
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
