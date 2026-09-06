import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import '../../i18n';
import PublicLayout from '../PublicLayout';

/**
 * Following a link between two long public pages lands at the top of the new one.
 *
 * <p>Reported, not hypothetical: "Pricing" clicked from partway down the home page opened
 * /pricing already scrolled into its FAQ, because the router leaves the offset where it was and
 * only the landing page reset it. A hash still wins — the nav links to #security, and jumping to
 * the top instead would break every one of those.
 */

const scrollTo = vi.fn();

beforeEach(() => {
  scrollTo.mockClear();
  vi.stubGlobal('scrollTo', scrollTo);
});
afterEach(() => vi.unstubAllGlobals());

function renderAt(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route element={<PublicLayout nav={false} />}>
          <Route path="/pricing" element={<p>Pricing</p>} />
          <Route path="/" element={<p>Home</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('public pages start at their top', () => {
  it('scrolls to the top on a plain navigation', () => {
    renderAt('/pricing');
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'auto' });
  });

  it('leaves a hash alone', () => {
    // #security is a real nav target on the landing page; hijacking it to the top would make
    // every anchor in the header do nothing.
    renderAt('/#security');
    expect(scrollTo).not.toHaveBeenCalled();
  });
});
