import { useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Rss } from 'lucide-react';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { useJsonLd } from '../hooks/useJsonLd';
import { blogPosts } from '../lib/blog';
import { siteUrl } from '../lib/siteUrl';
import { cn } from '../lib/utils';
import { Band, PageIntro, panel } from './landing/primitives';

/**
 * The blog's index: what has been published, newest first.
 *
 * Posts are files in `src/content/blog/`, so this page cannot fall behind them — the same
 * relationship /changelog has with CHANGELOG.md. The feed at /blog/rss.xml is written by the
 * `blogRss()` plugin in `vite.config.ts` from the same directory, and announced here as the
 * page's alternate representation, which is how a feed reader finds it.
 */

export const BLOG_FEED_PATH = '/blog/rss.xml';

/** The feed, announced in the head while the index is on screen. */
function useFeedLink(title: string) {
  useEffect(() => {
    const link = document.createElement('link');
    link.rel = 'alternate';
    link.type = 'application/rss+xml';
    link.title = title;
    link.href = BLOG_FEED_PATH;
    document.head.appendChild(link);
    return () => link.remove();
  }, [title]);
}

export function useBlogDateFormat() {
  const { i18n } = useTranslation();
  return useMemo(
    () => new Intl.DateTimeFormat(i18n.language, { dateStyle: 'long', timeZone: 'UTC' }),
    [i18n.language],
  );
}

/** A `YYYY-MM-DD` front-matter date as a `Date`, read as UTC so it never shifts a day. */
export function blogDate(date: string): Date {
  return new Date(`${date}T00:00:00Z`);
}

export function ReadingTime({ minutes }: { minutes: number }) {
  const { t } = useTranslation();
  // `{{minutes}}`, not i18next's `count`: a plural key expands to a different set of suffixes
  // per language (Ukrainian has four), and `locales.test.ts` requires the two files to hold
  // exactly the same key paths.
  return <span>{t('blog.readingTime', { minutes })}</span>;
}

export default function BlogPage() {
  const { t, i18n } = useTranslation();
  useDocumentMeta({ titleKey: 'meta.blog.title', descriptionKey: 'meta.blog.description', path: '/blog' });
  useFeedLink(t('blog.feedTitle'));

  const posts = blogPosts(i18n.language);
  const dates = useBlogDateFormat();
  const site = siteUrl();

  useJsonLd('blog', {
    '@context': 'https://schema.org',
    '@type': 'Blog',
    name: t('blog.title'),
    description: t('meta.blog.description'),
    url: `${site}/blog`,
    inLanguage: i18n.language.split('-')[0],
    blogPost: posts.map((post) => ({
      '@type': 'BlogPosting',
      headline: post.title,
      description: post.description,
      datePublished: post.date,
      author: { '@type': 'Person', name: post.author },
      url: `${site}/blog/${post.slug}`,
    })),
  });

  return (
    <>
      <PageIntro eyebrow={t('blog.eyebrow')} title={t('blog.title')} lead={t('blog.lead')}>
        <a
          href={BLOG_FEED_PATH}
          className="inline-flex min-h-10 items-center gap-2 text-sm font-medium text-primary hover:underline"
        >
          <Rss className="h-4 w-4" aria-hidden="true" />
          {t('blog.feed')}
        </a>
      </PageIntro>

      <Band labelledBy="blog-posts">
        <h2 id="blog-posts" className="sr-only">
          {t('blog.postsHeading')}
        </h2>
        {posts.length === 0 ? (
          <p className="text-muted-foreground">{t('blog.empty')}</p>
        ) : (
          <ul className="grid gap-5">
            {posts.map((post) => (
              <li key={post.slug}>
                <article className={cn('h-full p-6 sm:p-7', panel(true))}>
                  <div className="mono-label flex flex-wrap items-center gap-x-3 gap-y-1">
                    <time dateTime={post.date}>{dates.format(blogDate(post.date))}</time>
                    <span aria-hidden="true">·</span>
                    <ReadingTime minutes={post.readingMinutes} />
                  </div>
                  <h3 className="mt-3 font-display text-[1.45rem] font-bold leading-[1.15] tracking-[-0.025em] text-foreground [text-wrap:balance]">
                    <Link to={`/blog/${post.slug}`} className="transition-colors hover:text-primary">
                      {post.title}
                    </Link>
                  </h3>
                  <p className="mt-2.5 max-w-2xl text-[1.0125rem] leading-relaxed text-muted-foreground">{post.lead}</p>
                  {post.tags.length > 0 && (
                    <ul className="mt-4 flex flex-wrap gap-2">
                      {post.tags.map((tag) => (
                        <li
                          key={tag}
                          className="rounded-full bg-accent px-2.5 py-0.5 font-mono text-[11px] tracking-[0.04em] text-primary"
                        >
                          {tag}
                        </li>
                      ))}
                    </ul>
                  )}
                </article>
              </li>
            ))}
          </ul>
        )}
      </Band>
    </>
  );
}
