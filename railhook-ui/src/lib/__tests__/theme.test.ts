import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getTheme, isDarkApplied, setTheme, toggleTheme } from '../theme';

/** With nothing stored, 'system' on a dark machine made the first toggle choose dark again. */
describe('toggleTheme', () => {
  function systemPrefersDark(dark: boolean) {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: dark && query.includes('dark'),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }));
  }

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('light', 'dark');
  });

  it('turns a dark system default light on the first click', () => {
    systemPrefersDark(true);
    setTheme('system');
    expect(isDarkApplied()).toBe(true);

    expect(toggleTheme()).toBe('light');
    expect(isDarkApplied()).toBe(false);
    expect(getTheme()).toBe('light');
  });

  it('turns a light system default dark on the first click', () => {
    systemPrefersDark(false);
    setTheme('system');
    expect(isDarkApplied()).toBe(false);

    expect(toggleTheme()).toBe('dark');
    expect(isDarkApplied()).toBe(true);
  });

  it('keeps alternating after the first click', () => {
    systemPrefersDark(true);
    setTheme('system');

    expect(toggleTheme()).toBe('light');
    expect(toggleTheme()).toBe('dark');
    expect(toggleTheme()).toBe('light');
  });

  it('reports what is on the document, not what is stored', () => {
    systemPrefersDark(true);
    setTheme('system');

    expect(getTheme()).toBe('system');
    expect(isDarkApplied()).toBe(true);
  });
});
