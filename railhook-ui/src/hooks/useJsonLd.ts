import { useEffect } from 'react';

/** Marked data-page so it is replaced on a language change and removed on navigation. */
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
