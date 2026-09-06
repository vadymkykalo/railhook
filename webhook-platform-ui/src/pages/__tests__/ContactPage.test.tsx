import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import { renderPage } from '../../test/renderPage';

/**
 * The two mail cards used to be hardcoded to a domain this project does not
 * own, so a self-hosted install told its users to write to a stranger.
 *
 * The fix is not a different constant. A deployment someone runs for their own
 * company has no sales desk at all, so with no domain configured the cards are
 * absent rather than pointing somewhere — while the issues and documentation
 * cards, which are true on every deployment, stay.
 *
 * The page is re-imported per test because VITE_CONTACT_DOMAIN is read when the
 * module is evaluated.
 */
async function renderContact() {
  vi.resetModules();
  const { default: ContactPage } = await import('../ContactPage');
  renderPage(<ContactPage />, { path: '/contact', initialEntry: '/contact' });
}

describe('ContactPage', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('offers no mail addresses when no contact domain is configured', async () => {
    vi.stubEnv('VITE_CONTACT_DOMAIN', '');

    await renderContact();

    expect(document.querySelectorAll('a[href^="mailto:"]')).toHaveLength(0);
  });

  it('still points at the issue tracker with no contact domain, because that is always true', async () => {
    vi.stubEnv('VITE_CONTACT_DOMAIN', '');

    await renderContact();

    expect(screen.getByRole('link', { name: /issue/i })).toBeInTheDocument();
  });

  it('offers sales and support on the configured domain, and only that domain', async () => {
    vi.stubEnv('VITE_CONTACT_DOMAIN', 'example.com');

    await renderContact();

    const mails = Array.from(document.querySelectorAll('a[href^="mailto:"]')).map((a) =>
      a.getAttribute('href'),
    );
    expect(mails).toEqual(['mailto:sales@example.com', 'mailto:support@example.com']);
  });
});
