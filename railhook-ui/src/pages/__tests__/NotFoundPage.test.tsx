import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import NotFoundPage from '../NotFoundPage';
import { renderPage } from '../../test/renderPage';

describe('NotFoundPage', () => {
  function renderAt(entry: string, auth?: { isAuthenticated: boolean }) {
    return renderPage(<NotFoundPage />, { path: '*', initialEntry: entry, auth });
  }

  it('sends a signed-in user back to the dashboard', () => {
    renderAt('/admin/nope', { isAuthenticated: true });

    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', '/admin/dashboard');
  });

  it('sends a signed-out visitor back to the front page', () => {
    renderAt('/nope', { isAuthenticated: false });

    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', '/');
  });

  it('names the path that missed', () => {
    renderAt('/admin/typo', { isAuthenticated: true });

    expect(screen.getByText(/\/admin\/typo/)).toBeInTheDocument();
  });
});
