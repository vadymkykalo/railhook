import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import '../../../i18n';
import en from '../../../i18n/locales/en.json';
import { createTestQueryClient } from '../../../test/renderPage';
import { CONSENT_KEY } from '../../../lib/consent';
import SiteOverlays from '../SiteOverlays';

/**
 * The cookie notice and the message widget over the public pages. Each appears only where it
 * means something: the notice where analytics runs, the widget where support has an address.
 */
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
  it('asks once, and remembers a decline for analytics.js to read', async () => {
    renderAt();
    const decline = await screen.findByRole('button', { name: en.site.cookie.decline }, { timeout: 2000 });
    await userEvent.click(decline);

    expect(localStorage.getItem(CONSENT_KEY)).toBe('declined');
    expect(screen.queryByText(en.site.cookie.title)).not.toBeInTheDocument();
  });

  it('is not shown again once answered', async () => {
    localStorage.setItem(CONSENT_KEY, 'accepted');
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
