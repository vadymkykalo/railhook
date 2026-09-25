import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { RouterProvider } from 'react-router-dom';
import '../i18n';
import en from '../i18n/locales/en.json';
import { QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, type AuthState } from '../auth/auth.store';
import { createTestQueryClient, renderPage } from '../test/renderPage';
import LandingNav from '../pages/landing/LandingNav';
import PublicLayout from '../layout/PublicLayout';
import { publicBlogEnabled } from '../lib/runtimeConfig';
import { router } from '../router';

/**
 * The blog is railhook.io's own content. The one published image runs railhook.io and every
 * self-hosted install, so the blog is off unless the deployment turns it on (BLOG_ENABLED):
 * no Blog link in the header or the footer, and /blog renders the not-found page — the same
 * answer nginx has already given with a 404 status.
 */
const SIGNED_OUT = { auth: { user: null, token: null, isAuthenticated: false } };

beforeAll(() => {
  window.scrollTo = () => {};
  if (!('IntersectionObserver' in window)) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).IntersectionObserver = class {
      observe() {}
      disconnect() {}
    };
  }
});

afterEach(() => {
  delete window.__RAILHOOK__;
});

describe('publicBlogEnabled', () => {
  it('is on only for a literal true', () => {
    expect(publicBlogEnabled()).toBe(false);
    window.__RAILHOOK__ = { publicBlog: false };
    expect(publicBlogEnabled()).toBe(false);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    window.__RAILHOOK__ = { publicBlog: 'true' as any };
    expect(publicBlogEnabled()).toBe(false);
    window.__RAILHOOK__ = { publicBlog: true };
    expect(publicBlogEnabled()).toBe(true);
  });
});

describe('the header', () => {
  it('leaves the blog to the footer, whether or not it is on', () => {
    window.__RAILHOOK__ = { publicBlog: true };
    renderPage(<LandingNav />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    const hrefs = screen.getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(hrefs).not.toContain('/blog');
  });
});

describe('the footer', () => {
  const footerBlog = () => within(screen.getByRole('contentinfo')).queryByRole('link', { name: en.footer.blog });

  it('links the blog where the deployment serves it', () => {
    window.__RAILHOOK__ = { publicBlog: true };
    renderPage(<PublicLayout nav={false} />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    expect(footerBlog()).toHaveAttribute('href', '/blog');
  });

  it('does not when the blog is off', () => {
    renderPage(<PublicLayout nav={false} />, { path: '/', initialEntry: '/', ...SIGNED_OUT });
    expect(footerBlog()).toBeNull();
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: en.footer.about })).toBeInTheDocument();
  });
});

/** The app's real route table, as a signed-out visitor meets it. */
async function renderRoute(path: string) {
  const auth: AuthState = { user: null, token: null, isAuthenticated: false, login: () => {}, logout: () => {}, updateUser: () => {} };
  await router.navigate(path);
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <AuthContext.Provider value={auth}>
        <RouterProvider router={router} />
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe('the /blog routes', () => {
  it.each(['/blog', '/blog/what-is-a-webhook'])('render the not-found page at %s when the blog is off', async (path) => {
    await renderRoute(path);
    expect(await screen.findByRole('heading', { level: 1, name: en.notFound.title })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: en.blog.title })).toBeNull();
  });

  it('render the blog when it is on', async () => {
    window.__RAILHOOK__ = { publicBlog: true };
    await renderRoute('/blog');
    expect(await screen.findByRole('heading', { level: 1, name: en.blog.title })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: en.notFound.title })).toBeNull();
  });
});
