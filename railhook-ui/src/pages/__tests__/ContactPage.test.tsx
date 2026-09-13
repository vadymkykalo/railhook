import { afterEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import ContactPage from '../ContactPage';

/**
 * The two mail cards used to be hardcoded to a domain this project does not
 * own, so a self-hosted install told its users to write to a stranger.
 *
 * The fix is not a different constant. A deployment someone runs for their own
 * company has no sales desk at all, so with no domain configured the cards are
 * absent rather than pointing somewhere — while the issues and documentation
 * cards, which are true on every deployment, stay.
 *
 * The domain is a property of the container, not of the image: the published
 * image is the same on railhook.io and on every self-hosted install, so it is
 * read from `window.__RAILHOOK__` (written by the UI container at startup) when
 * the page renders. The page is imported once, above, which is what proves it
 * is read at render time and not frozen when the module is evaluated.
 */
function withRuntimeConfig(config: Window['__RAILHOOK__']) {
  window.__RAILHOOK__ = config;
}

function renderContact() {
  renderPage(<ContactPage />, { path: '/contact', initialEntry: '/contact' });
}

function mailtoHrefs() {
  return Array.from(document.querySelectorAll('a[href^="mailto:"]')).map((a) => a.getAttribute('href'));
}

describe('ContactPage', () => {
  afterEach(() => {
    delete window.__RAILHOOK__;
  });

  it('offers no mail addresses when the runtime config is missing', () => {
    renderContact();

    expect(mailtoHrefs()).toHaveLength(0);
  });

  it('offers no mail addresses when the contact domain is empty', () => {
    withRuntimeConfig({ contactDomain: '' });

    renderContact();

    expect(mailtoHrefs()).toHaveLength(0);
  });

  it('treats a blank contact domain as empty', () => {
    withRuntimeConfig({ contactDomain: '   ' });

    renderContact();

    expect(mailtoHrefs()).toHaveLength(0);
  });

  it('still points at the issue tracker with no contact domain, because that is always true', () => {
    renderContact();

    expect(screen.getByRole('link', { name: /issue/i })).toBeInTheDocument();
  });

  it('offers sales and support on the configured domain, and only that domain', () => {
    withRuntimeConfig({ contactDomain: 'example.org' });

    renderContact();

    expect(mailtoHrefs()).toEqual(['mailto:sales@example.org', 'mailto:support@example.org']);
    expect(screen.getByRole('link', { name: 'sales@example.org' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'support@example.org' })).toBeInTheDocument();
  });

  it('trims the configured domain', () => {
    withRuntimeConfig({ contactDomain: ' example.org ' });

    renderContact();

    expect(mailtoHrefs()).toEqual(['mailto:sales@example.org', 'mailto:support@example.org']);
  });
});
