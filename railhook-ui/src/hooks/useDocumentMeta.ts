import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

import { siteUrl } from '../lib/siteUrl';

/**
 * Per-route title, description, canonical and document language.
 *
 * The app is a single HTML file, so before this every route inherited the
 * landing page's `<title>` and description from `index.html` — /docs, /pricing
 * and /register all shared one. `<html lang>` was likewise frozen at "en" no
 * matter which locale the reader had chosen, which is wrong for a screen reader
 * and wrong for a translation tool.
 *
 * Deliberately not react-helmet: four DOM writes in an effect need no library,
 * and a library here would be a dependency on every route.
 *
 * `path` is the canonical path, not the current URL — that keeps query strings
 * and a trailing hash out of the canonical, which is the whole point of it.
 */

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

export function useDocumentMeta({
  titleKey,
  titleParams,
  descriptionKey,
  path,
}: {
  titleKey: string;
  /** Interpolation for the title, e.g. the documentation section's own name. */
  titleParams?: Record<string, string>;
  descriptionKey: string;
  path: string;
}) {
  const { t, i18n } = useTranslation();
  const title = titleParams ? t(`${titleKey}Section`, titleParams) : t(titleKey);
  const description = t(descriptionKey);

  useEffect(() => {
    const site = siteUrl();
    document.title = title;
    document.documentElement.lang = i18n.language.split('-')[0];

    upsertMeta('meta[name="description"]', 'name', 'description', description);
    upsertMeta('meta[property="og:title"]', 'property', 'og:title', title);
    upsertMeta('meta[property="og:description"]', 'property', 'og:description', description);
    upsertMeta('meta[property="og:url"]', 'property', 'og:url', `${site}${path}`);
    upsertMeta('meta[name="twitter:title"]', 'name', 'twitter:title', title);
    upsertMeta('meta[name="twitter:description"]', 'name', 'twitter:description', description);
    upsertCanonical(`${site}${path === '/' ? '/' : path}`);
  }, [title, description, path, i18n.language]);
}
