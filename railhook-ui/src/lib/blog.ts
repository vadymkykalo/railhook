import { parseFrontMatter } from './frontMatter';
import { parseMarkdown, type Document } from './markdown';

/** A missing locale falls back to English: a wrong-language article beats a 404. */

const FILES = import.meta.glob('../content/blog/*/*.md', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

export const BLOG_LOCALES = ['en', 'uk'] as const;
export type BlogLocale = (typeof BLOG_LOCALES)[number];

export interface BlogPost {
  slug: string;
  locale: BlogLocale;
  title: string;
  lead: string;
  description: string;
  date: string;
  author: string;
  tags: string[];
  sourcesCheckedOn?: string;
  image: string;
  body: string;
  document: Document;
  readingMinutes: number;
}

const WORDS_PER_MINUTE = 200;

const REQUIRED = ['title', 'lead', 'description', 'date', 'author'] as const;

function normalizeLocale(language: string | undefined): BlogLocale {
  const base = (language ?? '').toLowerCase().split(/[-_]/)[0];
  return (BLOG_LOCALES as readonly string[]).includes(base) ? (base as BlogLocale) : 'en';
}

function build(slug: string, locale: BlogLocale, source: string): BlogPost {
  const { values, lists, body } = parseFrontMatter(source);
  for (const field of REQUIRED) {
    if (!values[field]) {
      throw new Error(`blog post ${slug}/${locale}.md is missing front-matter field "${field}"`);
    }
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(values.date)) {
    throw new Error(`blog post ${slug}/${locale}.md has date "${values.date}", expected YYYY-MM-DD`);
  }
  const document = parseMarkdown(body);
  return {
    slug,
    locale,
    title: values.title,
    lead: values.lead,
    description: values.description,
    date: values.date,
    author: values.author,
    tags: lists.tags ?? [],
    sourcesCheckedOn: values.sourcesCheckedOn,
    image: values.image || `/blog/${slug}.png`,
    body,
    document,
    readingMinutes: Math.max(1, Math.round(document.words / WORDS_PER_MINUTE)),
  };
}

const POSTS: Map<string, Map<BlogLocale, BlogPost>> = (() => {
  const out = new Map<string, Map<BlogLocale, BlogPost>>();
  for (const [path, source] of Object.entries(FILES)) {
    const match = /\/blog\/([^/]+)\/([^/]+)\.md$/.exec(path);
    if (!match) continue;
    const [, slug, name] = match;
    if (!(BLOG_LOCALES as readonly string[]).includes(name)) continue;
    const locale = name as BlogLocale;
    const byLocale = out.get(slug) ?? new Map<BlogLocale, BlogPost>();
    byLocale.set(locale, build(slug, locale, source));
    out.set(slug, byLocale);
  }
  return out;
})();

function newestFirst(a: BlogPost, b: BlogPost): number {
  return b.date.localeCompare(a.date) || a.slug.localeCompare(b.slug);
}

export function blogPosts(language?: string): BlogPost[] {
  const locale = normalizeLocale(language);
  return [...POSTS.values()]
    .map((byLocale) => byLocale.get(locale) ?? byLocale.get('en'))
    .filter((post): post is BlogPost => Boolean(post))
    .sort(newestFirst);
}

export function blogPost(slug: string, language?: string): BlogPost | undefined {
  const byLocale = POSTS.get(slug);
  if (!byLocale) return undefined;
  return byLocale.get(normalizeLocale(language)) ?? byLocale.get('en');
}

export function blogNeighbours(slug: string, language?: string): { previous?: BlogPost; next?: BlogPost } {
  const posts = blogPosts(language);
  const index = posts.findIndex((post) => post.slug === slug);
  if (index === -1) return {};
  return { next: posts[index - 1], previous: posts[index + 1] };
}

export function blogSlugs(): string[] {
  return blogPosts('en').map((post) => post.slug);
}
