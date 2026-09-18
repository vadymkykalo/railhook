import { useEffect } from 'react';

/**
 * A page's own schema.org block, in the head while the page is mounted.
 *
 * `index.html` carries the site-wide block (the application and the organization). A page that
 * has more to say — /pricing's FAQ — adds it here, marked with `data-page` so it is replaced on
 * a language change and removed when the reader navigates away, rather than following them to
 * a page it does not describe. The prerender captures the head, so crawlers get it as HTML.
 */
export function useJsonLd(page: string, data: object) {
  const json = JSON.stringify(data);

  useEffect(() => {
    const script = document.createElement('script');
    script.type = 'application/ld+json';
    script.dataset.page = page;
    script.textContent = json;
    document.head.appendChild(script);
    return () => script.remove();
  }, [page, json]);
}
