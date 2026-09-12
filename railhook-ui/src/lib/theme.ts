type Theme = 'light' | 'dark' | 'system';

const STORAGE_KEY = 'theme';

export function getTheme(): Theme {
  return (localStorage.getItem(STORAGE_KEY) as Theme) || 'system';
}

export function setTheme(theme: Theme) {
  localStorage.setItem(STORAGE_KEY, theme);
  applyTheme(theme);
}

/**
 * Whether the dark palette is on the document right now — not what is stored. The two differ
 * whenever the stored value is 'system', which is the default, so it is the applied one every
 * decision here has to be made against.
 */
export function isDarkApplied(): boolean {
  return document.documentElement.classList.contains('dark');
}

/**
 * Flip to the opposite of what is on screen, and return what is now applied.
 *
 * <p>Inverting the stored value instead is the bug this replaces: with nothing stored
 * getTheme() answers 'system', 'system' is not 'dark', so the toggle chose 'dark' — which on a
 * machine set to dark is the theme already showing. The first click did nothing, every time,
 * for every user who had never chosen a theme.
 */
export function toggleTheme(): Exclude<Theme, 'system'> {
  const next = isDarkApplied() ? 'light' : 'dark';
  setTheme(next);
  return next;
}

export function applyTheme(theme: Theme) {
  const root = document.documentElement;
  root.classList.remove('light', 'dark');

  if (theme === 'system') {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    root.classList.add(prefersDark ? 'dark' : 'light');
  } else {
    root.classList.add(theme);
  }
}

export function initTheme() {
  applyTheme(getTheme());

  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (getTheme() === 'system') {
      applyTheme('system');
    }
  });
}
