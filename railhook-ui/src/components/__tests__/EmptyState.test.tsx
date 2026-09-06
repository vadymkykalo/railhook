import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Webhook } from 'lucide-react';
import EmptyState, { ErrorState } from '../EmptyState';

// Real i18n instance (initialized via side-effect import chain through '../../lib/toast' -> '../../i18n').
import '../../i18n';

function withRouter(ui: React.ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

describe('EmptyState', () => {
  it('renders title and description', () => {
    withRouter(<EmptyState icon={Webhook} title="No endpoints yet" description="Create your first endpoint" />);
    expect(screen.getByText('No endpoints yet')).toBeInTheDocument();
    expect(screen.getByText('Create your first endpoint')).toBeInTheDocument();
  });

  it('renders the provided action', () => {
    withRouter(
      <EmptyState icon={Webhook} title="No endpoints yet" action={<button>Create endpoint</button>} />
    );
    expect(screen.getByRole('button', { name: 'Create endpoint' })).toBeInTheDocument();
  });
});

describe('ErrorState', () => {
  it('renders as an alert, distinct from EmptyState, with a retry button', () => {
    withRouter(<ErrorState error={{}} onRetry={() => {}} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('does not render a retry button when onRetry is omitted', () => {
    withRouter(<ErrorState error={{}} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('calls onRetry when the retry button is clicked', () => {
    const onRetry = vi.fn();
    withRouter(<ErrorState error={{}} onRetry={onRetry} />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('surfaces the server-provided error message over the fallback key', () => {
    const err = { response: { status: 500, data: { message: 'Database connection pool exhausted' } } };
    withRouter(<ErrorState error={err} fallbackKey="endpoints.toast.loadFailed" />);
    expect(screen.getByText('Database connection pool exhausted')).toBeInTheDocument();
  });

  it('surfaces a distinct "backend unreachable" message for a network error, not a generic fallback', () => {
    const networkErr = { request: {}, message: 'Network Error' };
    withRouter(<ErrorState error={networkErr} fallbackKey="endpoints.toast.loadFailed" />);
    // Must not silently fall back to the generic "Failed to load data" copy —
    // a down backend needs to look different from a normal load failure.
    expect(screen.queryByText('Failed to load data')).not.toBeInTheDocument();
    expect(screen.getByText(/network error/i)).toBeInTheDocument();
  });

  it('disables the retry button and shows a spinner label while retrying', () => {
    withRouter(<ErrorState error={{}} onRetry={() => {}} retrying />);
    const button = screen.getByRole('button', { name: /retrying/i });
    expect(button).toBeDisabled();
  });
});
