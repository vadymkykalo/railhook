import { describe, expect, it } from 'vitest';
import { SETTINGS_SECTION, sectionFor } from '../nav.config';

describe('the Settings tab strip', () => {
  it('offers API keys, which the first event needs, beside the other settings', () => {
    const apiKeys = SETTINGS_SECTION.tabs.find((tab) => tab.nameKey === 'nav.apiKeys');
    expect(apiKeys).toBeDefined();
    expect(apiKeys!.path('project-1')).toBe('/admin/projects/project-1/api-keys');
    expect(apiKeys!.path(undefined)).toBe('/admin/start/api-keys');
  });

  it('is the strip shown on the API keys page', () => {
    expect(sectionFor('/admin/projects/project-1/api-keys')).toBe(SETTINGS_SECTION);
  });
});
