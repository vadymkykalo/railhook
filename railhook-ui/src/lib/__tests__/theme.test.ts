import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getTheme, isDarkApplied, setTheme, toggleTheme } from '../theme';

/**
 * The toggle used to invert the *stored* theme. With nothing stored getTheme() answers
 * 'system'; 'system' is not 'dark', so it chose 'dark' — which, on a machine set to dark, is
 * the theme already on screen. The first click did nothing, for every user who had never
 * picked a theme, which is all of them until they pick one.
 */
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
