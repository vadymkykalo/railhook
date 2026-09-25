/** Its own module so tests can replace it: jsdom will not let location.assign be spied on. */
export function leaveTo(url: string): void {
  window.location.assign(url);
}
