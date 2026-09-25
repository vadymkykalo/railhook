/** The docs are a separate static site: links must be a full page load, never a router navigation. */

const TRANSLATED = new Set(['uk']);

export function docsUrl(language: string | undefined, slug = ''): string {
  const lang = (language ?? '').toLowerCase().split(/[-_]/)[0];
  const prefix = TRANSLATED.has(lang) ? `${lang}/` : '';
  const path = slug.replace(/^\/+|\/+$/g, '');
  return `/docs/${prefix}${path ? `${path}/` : ''}`;
}
