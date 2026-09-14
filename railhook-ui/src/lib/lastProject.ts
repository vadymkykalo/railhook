/**
 * The project the dashboard was last in.
 *
 * Overview, the projects list and the org-level pages carry no project in their URL, and they used
 * to fall back to the account's first project — so opening Overview from inside "load-test" showed
 * "test", and the rail followed it. Remembering the last project opened keeps you where you were.
 *
 * Browser storage can be unavailable (a private window, blocked site data), so every access is
 * guarded and a failure simply means no project is remembered.
 */
const KEY = 'railhook:last-project';

export function rememberProject(projectId: string | undefined): void {
  if (!projectId) return;
  try {
    localStorage.setItem(KEY, projectId);
  } catch {
    // Nothing to remember into; the fallback is the first project.
  }
}

/** The remembered project if it is still one of `projects`, else the first of them. */
export function projectToOpen<T extends { id: string }>(projects: T[]): string | undefined {
  let remembered: string | null = null;
  try {
    remembered = localStorage.getItem(KEY);
  } catch {
    remembered = null;
  }
  if (remembered && projects.some((p) => p.id === remembered)) return remembered;
  return projects[0]?.id;
}
