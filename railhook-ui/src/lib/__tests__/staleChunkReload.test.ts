import { describe, it, expect, vi, beforeEach } from 'vitest';
import { installStaleChunkReload } from '../staleChunkReload';

function preloadError() {
  const event = new Event('vite:preloadError', { cancelable: true });
  window.dispatchEvent(event);
  return event;
}

describe('installStaleChunkReload', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('reloads the page once when a chunk from the previous deploy fails to load', () => {
    const reload = vi.fn();
    const uninstall = installStaleChunkReload(reload);

    const event = preloadError();

    expect(reload).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(true);
    uninstall();
  });

  it('does not reload again in the same session, so a chunk that is really gone cannot loop', () => {
    const reload = vi.fn();
    const uninstall = installStaleChunkReload(reload);

    preloadError();
    const second = preloadError();

    expect(reload).toHaveBeenCalledTimes(1);
    expect(second.defaultPrevented).toBe(false);
    uninstall();
  });
});
