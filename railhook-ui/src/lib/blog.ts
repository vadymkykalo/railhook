import { parseFrontMatter } from './frontMatter';
import { parseMarkdown, type Document } from './markdown';

/**
 * The blog's posts, read from `src/content/blog/` when the app is built.
 *
 * One directory per post, named for its slug, holding `en.md` and `uk.md`. Both are real
 * writing: the Ukrainian file is a translation, not a machine pass, and a post that is missing
 * one falls back to English rather than disappearing — a reader would rather have the article in
 * the wrong language than a 404.
 *
 * `import.meta.glob` rather than a virtual module or a generated index: Vite resolves it at build
 * time, the dev server watches the directory, and vitest gets the same posts the site does, so
 * adding a file is the whole of adding a post. `vite.config.ts` reads the same directory with
 * `fs` to write the RSS feed, through the same front-matter parser.
 *
 * `railhook-ui/src/content/blog/README.md` is the authoring contract.
 */

const FILES = import.meta.glob('../content/blog/*/*.md', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/** The languages a post is written in. `en` is the one every post must have. */
export const BLOG_LOCALES = ['en', 'uk'] as const;
export type BlogLocale = (typeof BLOG_LOCALES)[number];

export interface BlogPost {
  slug: string;
  locale: BlogLocale;
  title: string;
  /** The standfirst under the title. */
  lead: string;
  /** The meta description, which is not the lead: it is written for a search result. */
  description: string;
  /** Publication date, `YYYY-MM-DD`. */
  date: string;
  author: string;
  tags: string[];
  /**
   * The date every external claim in the post was last checked against its source. Providers
   * change their retry schedules; an article that quotes one owes the reader the date it was
   * true on.
   */
  sourcesCheckedOn?: string;
  /** The social card, `/blog/<slug>.png` unless the post names another. */
  image: string;
  /** The Markdown below the front matter. */
  body: string;
  /** Parsed once, here, so the index and the article agree on the reading time. */
  document: Document;
  readingMinutes: number;
}

/** What a reader gets through in a minute. The usual figure for prose on the web. */
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

/** slug → locale → post, built once for the life of the module. */
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

/** Newest first, then by slug so two posts on one day keep a stable order. */
function newestFirst(a: BlogPost, b: BlogPost): number {
  return b.date.localeCompare(a.date) || a.slug.localeCompare(b.slug);
}

/** Every post, in the reader's language where there is one. */
export function blogPosts(language?: string): BlogPost[] {
  const locale = normalizeLocale(language);
  return [...POSTS.values()]
    .map((byLocale) => byLocale.get(locale) ?? byLocale.get('en'))
    .filter((post): post is BlogPost => Boolean(post))
    .sort(newestFirst);
}

/** One post, or undefined when nothing is published under that slug. */
export function blogPost(slug: string, language?: string): BlogPost | undefined {
  const byLocale = POSTS.get(slug);
  if (!byLocale) return undefined;
  return byLocale.get(normalizeLocale(language)) ?? byLocale.get('en');
}

/**
 * The posts either side of this one, in reading order. `previous` is the older post — the one a
 * reader who has just finished has not read — and `next` the newer.
 */
export function blogNeighbours(slug: string, language?: string): { previous?: BlogPost; next?: BlogPost } {
  const posts = blogPosts(language);
  const index = posts.findIndex((post) => post.slug === slug);
  if (index === -1) return {};
  return { next: posts[index - 1], previous: posts[index + 1] };
}

/** Every slug the blog publishes, newest first. What the sitemap and the prerender enumerate. */
export function blogSlugs(): string[] {
  return blogPosts('en').map((post) => post.slug);
}
