import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ErrorBoundary } from '../ErrorBoundary';
import * as reporter from '../../lib/reportClientError';

describe('ErrorBoundary', () => {
  function Boom(): never {
    throw new Error('a component gave up');
  }

  beforeEach(() => {
    vi.restoreAllMocks();
    // React logs the caught error itself.
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('reports the failure, with the component React blamed', () => {
    const report = vi.spyOn(reporter, 'reportClientError').mockResolvedValue(undefined);

    render(<ErrorBoundary><Boom /></ErrorBoundary>);

    expect(report).toHaveBeenCalledTimes(1);
    const [error, context] = report.mock.calls[0];
    expect((error as Error).message).toBe('a component gave up');
    expect((context as { componentStack?: string })?.componentStack).toContain('Boom');
  });

  it('still shows its fallback when reporting throws outright', () => {
    vi.spyOn(reporter, 'reportClientError').mockImplementation(() => {
      throw new Error('the reporter is broken too');
    });

    expect(() => render(<ErrorBoundary><Boom /></ErrorBoundary>)).not.toThrow();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('renders its children when nothing throws', () => {
    render(<ErrorBoundary><p>all fine</p></ErrorBoundary>);

    expect(screen.getByText('all fine')).toBeInTheDocument();
  });
});
