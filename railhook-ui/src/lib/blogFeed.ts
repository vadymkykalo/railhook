/**
 * The blog's RSS feed, as text.
 *
 * Split out from the `blogRss()` plugin in `vite.config.ts` so the feed can be asserted on
 * without running a build: the plugin does the file reading and the emitting, this decides what
 * the XML says. Nothing here touches the filesystem or the DOM.
 */

export interface FeedItem {
  slug: string;
  title: string;
  description: string;
  /** `YYYY-MM-DD`. */
  date: string;
  author?: string;
}

/** Five characters, because a title with an ampersand in it must not break the document. */
export function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

/** A front-matter date as the RFC 822 stamp RSS wants, read as UTC so it never shifts a day. */
export function rfc822(date: string): string {
  return new Date(`${date}T00:00:00Z`).toUTCString();
}

/**
 * `site` is an origin with no trailing slash. At build time it is the placeholder nginx
 * substitutes with the container's RAILHOOK_SITE_URL, for the same reason the sitemap and every
 * canonical carry it: the image is built once for every deployment.
 *
 * One feed, in English. The site serves one URL per page in both languages and chooses the
 * language in the browser, so there is no second set of URLs a second feed could point at.
 */
export function renderFeed(site: string, items: FeedItem[]): string {
  const newest = items[0]?.date;
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    // `dc:creator` is how a feed reader shows a byline; RSS 2.0's own `<author>` is an email
    // address, and the author of these posts does not want one published.
    '<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/">',
    '  <channel>',
    '    <title>Railhook blog</title>',
    `    <link>${site}/blog</link>`,
    '    <description>Webhooks, delivery and the things that go wrong with them.</description>',
    '    <language>en</language>',
    `    <atom:link href="${site}/blog/rss.xml" rel="self" type="application/rss+xml"/>`,
    ...(newest ? [`    <lastBuildDate>${rfc822(newest)}</lastBuildDate>`] : []),
    ...items.flatMap((item) => [
      '    <item>',
      `      <title>${escapeXml(item.title)}</title>`,
      `      <link>${site}/blog/${item.slug}</link>`,
      `      <guid isPermaLink="true">${site}/blog/${item.slug}</guid>`,
      `      <pubDate>${rfc822(item.date)}</pubDate>`,
      ...(item.author ? [`      <dc:creator>${escapeXml(item.author)}</dc:creator>`] : []),
      `      <description>${escapeXml(item.description)}</description>`,
      '    </item>',
    ]),
    '  </channel>',
    '</rss>',
    '',
  ].join('\n');
}
