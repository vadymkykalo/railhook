import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import SectionTabs from '../SectionTabs';
import type { Role } from '../../auth/ProtectedRoute';

function renderTabs(path: string, role: Role = 'OWNER') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SectionTabs projectId="project-1" role={role} />
    </MemoryRouter>,
  );
}

/**
 * API keys were reachable only from the command palette and the projects list: Settings owned
 * the route, so the strip showed on the page, but no tab in it led there or lit up once you were.
 */
describe('the API keys tab', () => {
  it('is in the settings strip, and current on the API keys page', () => {
    renderTabs('/admin/projects/project-1/api-keys');
    const tab = screen.getByRole('link', { name: en.nav.apiKeys });
    expect(tab).toHaveAttribute('href', '/admin/projects/project-1/api-keys');
    expect(tab).toHaveAttribute('aria-current', 'page');
  });

  it('is offered from the profile page too, where every member lands in Settings', () => {
    renderTabs('/admin/settings', 'VIEWER');
    expect(screen.getByRole('link', { name: en.nav.apiKeys })).not.toHaveAttribute('aria-current');
  });
});
