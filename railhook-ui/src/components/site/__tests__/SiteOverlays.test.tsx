import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import '../../../i18n';
import en from '../../../i18n/locales/en.json';
import { createTestQueryClient } from '../../../test/renderPage';
import { NOTICE_KEY } from '../../../lib/consent';
import SiteOverlays from '../SiteOverlays';

function renderAt(path = '/') {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <SiteOverlays />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
  window.__RAILHOOK__ = { contactDomain: 'railhook.io', webAnalyticsToken: 'abc123' };
});

afterEach(() => {
  localStorage.clear();
  delete window.__RAILHOOK__;
});

describe('cookie notice', () => {
  it('says once what the site stores, and goes away when acknowledged', async () => {
    renderAt();
    const ok = await screen.findByRole('button', { name: en.site.cookie.ok }, { timeout: 2000 });
    expect(screen.getByRole('link', { name: en.site.cookie.policy })).toHaveAttribute('href', '/privacy');
    await userEvent.click(ok);

    expect(localStorage.getItem(NOTICE_KEY)).toBe('seen');
    expect(screen.queryByText(en.site.cookie.title)).not.toBeInTheDocument();
  });

  it('is not shown again once answered', async () => {
    localStorage.setItem(NOTICE_KEY, 'seen');
    renderAt();
    await new Promise((resolve) => setTimeout(resolve, 1100));
    expect(screen.queryByText(en.site.cookie.title)).not.toBeInTheDocument();
  });

  it('is not shown where the deployment runs no analytics', async () => {
    window.__RAILHOOK__ = { contactDomain: 'railhook.io' };
    renderAt();
    await new Promise((resolve) => setTimeout(resolve, 1100));
    expect(screen.queryByText(en.site.cookie.title)).not.toBeInTheDocument();
  });
});

describe('message widget', () => {
  it('opens the form from the corner and closes on Escape', async () => {
    renderAt();
    await userEvent.click(screen.getByRole('button', { name: en.site.contact.launcher }));
    expect(screen.getByRole('dialog', { name: en.site.contact.widgetTitle })).toBeVisible();
    expect(screen.getByLabelText(en.site.contact.emailLabel)).toHaveFocus();

    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('is absent without a support address, and on the contact page itself', () => {
    window.__RAILHOOK__ = {};
    const { unmount } = renderAt();
    expect(screen.queryByRole('button', { name: en.site.contact.launcher })).not.toBeInTheDocument();
    unmount();

    window.__RAILHOOK__ = { contactDomain: 'railhook.io' };
    renderAt('/contact');
    expect(screen.queryByRole('button', { name: en.site.contact.launcher })).not.toBeInTheDocument();
  });
});

describe('message widget draft', () => {
  it('keeps a half-written message when the panel is closed and opened again', async () => {
    renderAt();
    const launcher = screen.getByRole('button', { name: en.site.contact.launcher });
    await userEvent.click(launcher);
    await userEvent.type(screen.getByLabelText(en.site.contact.messageLabel), 'Half a thought');
    await userEvent.keyboard('{Escape}');
    await userEvent.click(launcher);

    expect(screen.getByLabelText(en.site.contact.messageLabel)).toHaveValue('Half a thought');
  });
});

describe('the closed widget', () => {
  it('covers nothing: it is display:none, not an invisible sheet over the page', async () => {
    renderAt();
    const panel = document.querySelector('[role="dialog"]') as HTMLElement;
    expect(panel).toHaveAttribute('hidden');
    // The attribute alone lost to a responsive display class and the sheet swallowed every tap.
    expect(panel.className).toContain('hidden');
    expect(panel.className).not.toContain('max-sm:fixed');

    await userEvent.click(screen.getByRole('button', { name: en.site.contact.launcher }));
    const open = document.querySelector('[role="dialog"]') as HTMLElement;
    expect(open).not.toHaveAttribute('hidden');
    expect(open.className).toContain('max-sm:fixed');
  });
});
