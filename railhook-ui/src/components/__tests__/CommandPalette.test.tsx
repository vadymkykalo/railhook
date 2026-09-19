import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { list: vi.fn().mockResolvedValue([]) },
}));

import { CommandPalette } from '../CommandPalette';

function openPalette() {
  renderPage(<CommandPalette />, {
    path: '/admin/projects/:projectId/*',
    initialEntry: `/admin/projects/${TEST_PROJECT_ID}/connections`,
  });
  fireEvent.keyDown(document, { key: 'k', ctrlKey: true });
  return screen.getByRole('listbox');
}

/**
 * The endpoint and subscription tables left the tab strip when Connections became the one list of
 * where events go. Someone who knew them by name still types "endpoints" into ⌘K, and has to find
 * them there.
 */
describe('the command palette', () => {
  it('still finds the tables that left the tab strip, filed under Send events', () => {
    const results = openPalette();
    const option = within(results).getByRole('option', { name: /^Endpoints/ });
    expect(option).toBeInTheDocument();
    expect(within(results).getByRole('option', { name: /^Subscriptions/ })).toBeInTheDocument();
    expect(within(results).getByText('Send events')).toBeInTheDocument();
  });

  it('files the transform studio under nothing: it is the Transformations editor now', () => {
    const results = openPalette();
    expect(within(results).queryByRole('option', { name: /Transform Studio/ })).not.toBeInTheDocument();
  });
});
