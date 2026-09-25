import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import '../../i18n';
import PublicLayout from '../PublicLayout';

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
    // #run is a real nav target; forcing the top would break every header anchor.
    renderAt('/#run');
    expect(scrollTo).not.toHaveBeenCalled();
  });
});
