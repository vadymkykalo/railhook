import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RouterProvider } from 'react-router-dom';
import '../i18n';

// What a browser holding the previous deploy's index.html sees: the page's chunk is gone.
vi.mock('../auth/LoginPage', () => {
  throw new TypeError('Failed to fetch dynamically imported module: /assets/LoginPage-old.js');
});

import { router } from '../router';

describe('a route whose chunk fails to load', () => {
  it("shows the app's own error screen with a reload, not React Router's bare default", async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    sessionStorage.setItem('railhook:stale-chunk-reload', String(Date.now()));
    await router.navigate('/login');

    render(<RouterProvider router={router} />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reload/i })).toBeInTheDocument();
    expect(screen.queryByText(/unexpected application error/i)).not.toBeInTheDocument();
  });
});
