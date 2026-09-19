import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '../../i18n';
import SectionTabs from '../SectionTabs';

function stubWidths({ scrollWidth, clientWidth, scrollLeft = 0 }: { scrollWidth: number; clientWidth: number; scrollLeft?: number }) {
  vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(scrollWidth);
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(clientWidth);
  vi.spyOn(HTMLElement.prototype, 'scrollLeft', 'get').mockReturnValue(scrollLeft);
}

function renderStrip() {
  return render(
    <MemoryRouter initialEntries={['/admin/projects/project-1/connections']}>
      <SectionTabs projectId="project-1" role="OWNER" />
    </MemoryRouter>,
  );
}

/**
 * At 375px the Connections strip holds nine tabs and shows three. Nothing said there were more:
 * the strip scrolled, but it looked like it ended at the screen's edge.
 */
describe('the section tab strip on a narrow screen', () => {
  afterEach(() => vi.restoreAllMocks());

  it('fades out on the side where more tabs are hidden', () => {
    stubWidths({ scrollWidth: 900, clientWidth: 375 });
    renderStrip();
    const strip = screen.getByRole('navigation');
    expect(strip.parentElement).toHaveAttribute('data-more-end', 'true');
    expect(strip.parentElement).not.toHaveAttribute('data-more-start');
  });

  it('shows no fade when every tab fits', () => {
    stubWidths({ scrollWidth: 800, clientWidth: 1200 });
    renderStrip();
    const strip = screen.getByRole('navigation');
    expect(strip.parentElement).not.toHaveAttribute('data-more-end');
    expect(strip.parentElement).not.toHaveAttribute('data-more-start');
  });
});
