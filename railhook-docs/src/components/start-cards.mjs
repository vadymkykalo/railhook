import { getCollection } from 'astro:content';

/**
 * Keeps only the cards whose page exists, and gives each its URL.
 *
 * One collection read rather than a lookup per card: asking for an entry that is not there
 * logs a warning per miss, and a missing page is the expected state here, not a problem.
 *
 * @param {Array<{ title: string, description?: string, slug?: string, href?: string }>} cards
 * @param {string | undefined} locale Starlight's locale for the page (`undefined` for English)
 * @param {string} base the site base, without a trailing slash (`/docs`)
 */
export async function resolveStartCards(cards, locale, base) {
  const prefix = locale ? locale + '/' : '';
  const ids = new Set((await getCollection('docs')).map((entry) => entry.id));
  return cards
    .filter((card) => card.href || (card.slug && ids.has(prefix + card.slug)))
    .map((card) => (card.href ? card : { ...card, href: base + '/' + prefix + card.slug + '/' }));
}
