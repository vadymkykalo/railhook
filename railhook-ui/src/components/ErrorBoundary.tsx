import { Component, type ReactNode } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { Button } from './ui/button';
import i18n from '../i18n';
import { reportClientError } from '../lib/reportClientError';

interface Props {
  children: ReactNode;
  /** `page` keeps a render error inside the content area so the shell stays usable. */
  variant?: 'app' | 'page';
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    // Reported to this installation's logs, or a screen that throws for everyone looks unopened.
    console.error('ErrorBoundary caught:', error, info.componentStack);
    try {
      void reportClientError(error, { componentStack: info.componentStack ?? undefined });
    } catch {
      // If componentDidCatch throws, React renders a blank page instead of this apology.
    }
  }

  render() {
    if (this.state.hasError) {
      return <ErrorFallback error={this.state.error} variant={this.props.variant} />;
    }

    return this.props.children;
  }
}

/** Also the router's errorElement, for lazy chunks a new deploy removed. */
export function ErrorFallback({ error, variant }: { error: Error | null; variant?: 'app' | 'page' }) {
  const page = variant === 'page';
  const reload = () => window.location.reload();
  const goToDashboard = () => { window.location.href = '/admin/dashboard'; };
  return (
    <div
      className={
        page
          ? 'flex min-h-[60vh] items-center justify-center p-6'
          : 'flex min-h-screen items-center justify-center bg-background p-6'
      }
    >
      <div role="alert" className="w-full max-w-md text-center">
        <div className="mx-auto mb-5 flex h-11 w-11 items-center justify-center border border-halt/30 bg-halt-soft">
          <AlertTriangle className="h-5 w-5 text-halt" aria-hidden />
        </div>
        {page ? (
          <h2 className="text-title">{i18n.t('errorBoundary.title')}</h2>
        ) : (
          <h1 className="text-title">{i18n.t('errorBoundary.title')}</h1>
        )}
        <p className="mt-1.5 text-sm text-muted-foreground">
          {i18n.t('errorBoundary.description')}
        </p>
        {error && (
          <pre className="mt-5 max-h-32 overflow-auto border border-rail bg-card p-3 text-left font-mono text-xs text-muted-foreground">
            {error.message}
          </pre>
        )}
        <div className="mt-6 flex items-center justify-center gap-2">
          <Button variant="outline" onClick={reload}>
            <RefreshCw className="h-4 w-4" aria-hidden /> {i18n.t('errorBoundary.reloadPage')}
          </Button>
          <Button onClick={goToDashboard}>
            {i18n.t('errorBoundary.goToDashboard')}
          </Button>
        </div>
      </div>
    </div>
  );
}
