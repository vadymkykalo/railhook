import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import GoogleSignInButton from '../GoogleSignInButton';
import { authApi } from '../../api/auth.api';

/**
 * The button is a promise that Google sign-in works here, so it must appear only where the API says
 * it is configured — and the error Google's round trip came back with has to reach the person.
 */
describe('GoogleSignInButton', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  function renderButton(entry = '/login', returnTo?: string) {
    return render(
      <MemoryRouter initialEntries={[entry]}>
        <GoogleSignInButton intent="login" returnTo={returnTo} />
      </MemoryRouter>,
    );
  }

  it('links to the API start endpoint when the deployment has Google configured', async () => {
    vi.spyOn(authApi, 'providers').mockResolvedValue({ google: true });

    renderButton('/login', '/admin/projects');

    const link = await screen.findByRole('link', { name: /google/i });
    const href = new URL(link.getAttribute('href') ?? '', 'http://localhost');
    expect(href.pathname).toBe('/api/v1/auth/oauth/google/start');
    expect(href.searchParams.get('intent')).toBe('login');
    expect(href.searchParams.get('returnTo')).toBe('/admin/projects');
  });

  it('shows nothing when Google is not configured', async () => {
    const providers = vi.spyOn(authApi, 'providers').mockResolvedValue({ google: false });

    renderButton();

    await waitFor(() => expect(providers).toHaveBeenCalled());
    expect(screen.queryByRole('link', { name: /google/i })).not.toBeInTheDocument();
  });

  it('shows nothing when the API cannot say', async () => {
    const providers = vi.spyOn(authApi, 'providers').mockRejectedValue(new Error('Network Error'));

    renderButton();

    await waitFor(() => expect(providers).toHaveBeenCalled());
    expect(screen.queryByRole('link', { name: /google/i })).not.toBeInTheDocument();
  });

  it('says why a Google sign-in came back without signing in', async () => {
    vi.spyOn(authApi, 'providers').mockResolvedValue({ google: true });

    renderButton('/login?error=google_denied');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent?.trim()).not.toBe('');
    expect(alert).not.toHaveTextContent('auth.google');
  });

  it('ignores an error parameter that is not about Google', async () => {
    vi.spyOn(authApi, 'providers').mockResolvedValue({ google: true });

    renderButton('/login?error=<script>');

    await screen.findByRole('link', { name: /google/i });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
