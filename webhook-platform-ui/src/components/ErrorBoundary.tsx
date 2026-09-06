import { Component, type ReactNode } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { Button } from './ui/button';
import i18n from '../i18n';

interface Props {
  children: ReactNode;
  /**
   * `page` keeps the failure inside the content area, leaving the shell — sidebar, project
   * switcher, navigation — alive and usable. Without it the app had exactly one boundary, at
   * the root, so a render error anywhere took the whole dashboard down and the only way out
   * was a reload. The heaviest pages are the likeliest to throw and the least likely to be
   * where the user wants to stay.
   */
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
    console.error('ErrorBoundary caught:', error, info.componentStack);
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null });
    window.location.href = '/admin/dashboard';
  };

  handleReload = () => {
    window.location.reload();
  };

  render() {
    if (this.state.hasError) {
      const page = this.props.variant === 'page';
      return (
        <div
          className={
            page
              ? 'flex min-h-[60vh] items-center justify-center p-6'
              : 'flex min-h-screen items-center justify-center bg-background p-6'
          }
        >
          <div role="alert" className="w-full max-w-md text-center">
            <div className="mx-auto mb-5 flex h-11 w-11 items-center justify-center rounded-lg border border-halt/30 bg-halt-soft">
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
            {this.state.error && (
              <pre className="mt-5 max-h-32 overflow-auto rounded-md border border-rail bg-card p-3 text-left font-mono text-xs text-muted-foreground">
                {this.state.error.message}
              </pre>
            )}
            <div className="mt-6 flex items-center justify-center gap-2">
              <Button variant="outline" onClick={this.handleReload}>
                <RefreshCw className="h-4 w-4" aria-hidden /> {i18n.t('errorBoundary.reloadPage')}
              </Button>
              <Button onClick={this.handleReset}>
                {i18n.t('errorBoundary.goToDashboard')}
              </Button>
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
