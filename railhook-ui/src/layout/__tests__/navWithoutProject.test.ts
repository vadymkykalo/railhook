import { describe, it, expect } from 'vitest';
import { PROJECT_SECTIONS, sectionFor, segmentOf } from '../nav.config';

describe('navigation without a project', () => {
  const projectScoped = PROJECT_SECTIONS.filter((section) => section.path('project-1').includes('/projects/'));

  it('finds the sections it is meant to be checking', () => {
    expect(projectScoped.length).toBeGreaterThanOrEqual(5);
  });

  it('sends every project-scoped section somewhere distinct, never back to the projects list', () => {
    const destinations = projectScoped.map((section) => section.path(undefined));
    for (const to of destinations) {
      expect(to).toMatch(/^\/admin\/start\/[a-z-]+$/);
    }
    expect(new Set(destinations).size).toBe(destinations.length);
  });

  it('keeps the section highlighted on its setup screen', () => {
    for (const section of projectScoped) {
      const to = section.path(undefined);
      expect(section.owns).toContain(segmentOf(to));
      expect(sectionFor(to)).toBe(section);
    }
  });

  it('still builds project URLs when there is a project', () => {
    const events = PROJECT_SECTIONS.find((s) => s.nameKey === 'nav.events')!;
    expect(events.path('project-1')).toBe('/admin/projects/project-1/events');
  });
});
