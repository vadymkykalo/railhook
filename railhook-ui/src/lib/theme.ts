type Theme = 'light' | 'dark' | 'system';

const STORAGE_KEY = 'theme';

export function getTheme(): Theme {
  return (localStorage.getItem(STORAGE_KEY) as Theme) || 'system';
}

export function setTheme(theme: Theme) {
  localStorage.setItem(STORAGE_KEY, theme);
  applyTheme(theme);
}

/** The applied theme, not the stored one: stored 'system' can mean either. */
export function isDarkApplied(): boolean {
  return document.documentElement.classList.contains('dark');
}

/** Flip what is on screen: inverting a stored 'system' made the first click a no-op. */
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
