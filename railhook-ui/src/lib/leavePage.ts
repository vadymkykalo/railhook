/**
 * A full navigation away from the app, to another site. Its own module so a test can replace it:
 * jsdom will not let `window.location.assign` be spied on.
 */
export function leaveTo(url: string): void {
  window.location.assign(url);
}
