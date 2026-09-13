/**
 * The docs are a separate static site served at /docs/ by the same nginx: English at the
 * root, a full Ukrainian copy under /docs/uk/. There is no /docs route in the app, so a link
 * built here must be a plain `<a href>` (or `window.location`), never a router navigation.
 */

/** Languages the docs are written in besides the English root. */
const TRANSLATED = new Set(['uk']);

/**
 * @param language the reader's i18next language, e.g. `uk` or `en-GB`
 * @param slug a page in the docs slug contract, e.g. `outgoing/retries`; empty for the home
 */
export function docsUrl(language: string | undefined, slug = ''): string {
  const lang = (language ?? '').toLowerCase().split(/[-_]/)[0];
  const prefix = TRANSLATED.has(lang) ? `${lang}/` : '';
  const path = slug.replace(/^\/+|\/+$/g, '');
  return `/docs/${prefix}${path ? `${path}/` : ''}`;
}
