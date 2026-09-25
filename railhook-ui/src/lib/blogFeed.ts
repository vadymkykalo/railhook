/** Split from the vite plugin so the XML can be tested without a build; no fs or DOM here. */

export interface FeedItem {
  slug: string;
  title: string;
  description: string;
  date: string;
  author?: string;
}

export function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

/** Read as UTC so it never shifts a day. */
export function rfc822(date: string): string {
  return new Date(`${date}T00:00:00Z`).toUTCString();
}

/** `site` is the placeholder nginx swaps for RAILHOOK_SITE_URL: one image serves every deployment. */
export function renderFeed(site: string, items: FeedItem[]): string {
  const newest = items[0]?.date;
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    // dc:creator, not <author>: RSS author is an email address, and none is published.
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
