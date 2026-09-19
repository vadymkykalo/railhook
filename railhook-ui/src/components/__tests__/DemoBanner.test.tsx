import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import DemoBanner from '../DemoBanner';

/**
 * At 375px the banner wrapped onto several lines above every page of the demo,
 * pushing the page's own heading below the fold. On a phone it is one line: what this is, the
 * one thing to do, and a way out.
 */
describe('DemoBanner', () => {
  it('says it in two words on a phone and in full from sm up', () => {
    render(<DemoBanner onStartFree={vi.fn()} onExit={vi.fn()} />);
    expect(screen.getByText(en.demo.bannerShort)).toHaveClass('sm:hidden');
    expect(screen.getByText(en.demo.bannerBody)).toHaveClass('max-sm:hidden');
  });

  it('keeps the exit reachable by name when it shrinks to an icon', () => {
    render(<DemoBanner onStartFree={vi.fn()} onExit={vi.fn()} />);
    expect(screen.getByRole('button', { name: en.demo.exit })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.demo.startFree })).toBeInTheDocument();
  });
});
