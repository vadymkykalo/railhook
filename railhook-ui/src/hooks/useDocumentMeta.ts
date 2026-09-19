import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

import { siteUrl } from '../lib/siteUrl';

/**
 * Per-route title, description, canonical, social image and document language.
 *
 * The app is a single HTML file, so before this every route inherited the
 * landing page's `<title>` and description from `index.html` — /docs, /pricing
 * and /register all shared one. `<html lang>` was likewise frozen at "en" no
 * matter which locale the reader had chosen, which is wrong for a screen reader
 * and wrong for a translation tool.
 *
 * Deliberately not react-helmet: a handful of DOM writes in an effect need no library,
 * and a library here would be a dependency on every route.
 *
 * `path` is the canonical path, not the current URL — that keeps query strings
 * and a trailing hash out of the canonical, which is the whole point of it.
 *
 * Most routes name translation keys. A blog post cannot: its title is written in a Markdown
 * file, not in the locale bundles, so it passes the strings themselves. One or the other, never
 * both — the union below is what enforces that.
 *
 * No hreflang alternates are written, here or anywhere else: this site serves one URL per page
 * in both languages and chooses the language in the browser, so there is no second URL to point
 * a crawler at. `og:locale` says which language the crawled copy is in, and that is the whole of
 * the app's i18n contract with a crawler.
 */

type Keyed = {
  titleKey: string;
  /** Interpolation for the title, e.g. the documentation section's own name. */
  titleParams?: Record<string, string>;
  descriptionKey: string;
  title?: never;
  description?: never;
};

type Literal = {
  title: string;
  description: string;
  titleKey?: never;
  titleParams?: never;
  descriptionKey?: never;
};

type DocumentMeta = (Keyed | Literal) & {
  path: string;
  /**
   * The social card for this page, as a site-absolute path. Always written, so a reader who
   * leaves an article for the landing page does not leave the article's card behind in the head.
   */
  image?: string;
};

/** What `index.html` names, and what every page without one of its own falls back to. */
const DEFAULT_IMAGE = '/social-card.png';

function upsertMeta(selector: string, attr: 'name' | 'property', key: string, content: string) {
  let tag = document.head.querySelector<HTMLMetaElement>(selector);
  if (!tag) {
    tag = document.createElement('meta');
    tag.setAttribute(attr, key);
    document.head.appendChild(tag);
  }
  tag.setAttribute('content', content);
}

function upsertCanonical(href: string) {
  let link = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
  if (!link) {
    link = document.createElement('link');
    link.setAttribute('rel', 'canonical');
    document.head.appendChild(link);
  }
  link.setAttribute('href', href);
}

export function useDocumentMeta(meta: DocumentMeta) {
  const { t, i18n } = useTranslation();
  const title = meta.title
    ?? (meta.titleParams ? t(`${meta.titleKey}Section`, meta.titleParams) : t(meta.titleKey as string));
  const description = meta.description ?? t(meta.descriptionKey as string);
  const { path, image = DEFAULT_IMAGE } = meta;

  useEffect(() => {
    const site = siteUrl();
    document.title = title;
    const lang = i18n.language.split('-')[0];
    document.documentElement.lang = lang;

    upsertMeta('meta[name="description"]', 'name', 'description', description);
    upsertMeta('meta[property="og:title"]', 'property', 'og:title', title);
    upsertMeta('meta[property="og:description"]', 'property', 'og:description', description);
    upsertMeta('meta[property="og:url"]', 'property', 'og:url', `${site}${path}`);
    upsertMeta('meta[property="og:locale"]', 'property', 'og:locale', lang === 'uk' ? 'uk_UA' : 'en_US');
    upsertMeta('meta[property="og:image"]', 'property', 'og:image', `${site}${image}`);
    upsertMeta('meta[name="twitter:title"]', 'name', 'twitter:title', title);
    upsertMeta('meta[name="twitter:description"]', 'name', 'twitter:description', description);
    upsertMeta('meta[name="twitter:image"]', 'name', 'twitter:image', `${site}${image}`);
    upsertCanonical(`${site}${path === '/' ? '/' : path}`);
  }, [title, description, path, image, i18n.language]);
}
