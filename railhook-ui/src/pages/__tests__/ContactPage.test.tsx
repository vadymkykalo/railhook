import { afterEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import ContactPage from '../ContactPage';

/** Imported once, so this proves the domain is read at render, not at module load. */
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
