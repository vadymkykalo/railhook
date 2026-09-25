import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

import { siteUrl } from '../lib/siteUrl';

/** No hreflang: one URL serves both languages, chosen in the browser; og:locale is the whole contract. */

type Keyed = {
  titleKey: string;
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
  /** Always written, so leaving an article does not leave its card in the head. */
  image?: string;
};

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
