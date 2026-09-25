/** Storage may be unavailable (private window), so every access is guarded. */
const KEY = 'railhook:last-project';

export function rememberProject(projectId: string | undefined): void {
  if (!projectId) return;
  try {
    localStorage.setItem(KEY, projectId);
  } catch {
    // Storage refused; the fallback is the first project.
  }
}

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
