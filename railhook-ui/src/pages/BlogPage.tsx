import { useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Rss } from 'lucide-react';
import { BYLINE, TagList } from '../components/blog/PostMeta';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { useJsonLd } from '../hooks/useJsonLd';
import { blogPosts } from '../lib/blog';
import { siteUrl } from '../lib/siteUrl';
import { cn } from '../lib/utils';
import { Band, PageIntro, panel } from './landing/primitives';

export const BLOG_FEED_PATH = '/blog/rss.xml';

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

/** Read as UTC so it never shifts a day. */
export function blogDate(date: string): Date {
  return new Date(`${date}T00:00:00Z`);
}

export function ReadingTime({ minutes }: { minutes: number }) {
  const { t } = useTranslation();
  // {{minutes}}, not count: plural suffixes differ per language and locales.test.ts needs identical keys.
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
          className="inline-flex min-h-10 items-center gap-2 text-sm font-medium link-ink"
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
                {/* Stretched link: a screen reader hears one link named by the title, not the whole card. */}
                <article
                  className={cn(
                    'group relative h-full cursor-pointer p-6 sm:p-7',
                    panel(true),
                    'has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-ring has-[a:focus-visible]:ring-offset-2 has-[a:focus-visible]:ring-offset-background',
                  )}
                >
                  <div className={BYLINE}>
                    <time dateTime={post.date}>{dates.format(blogDate(post.date))}</time>
                    <span aria-hidden="true">·</span>
                    <ReadingTime minutes={post.readingMinutes} />
                  </div>
                  <h3 className="mt-3 text-[1.45rem] font-medium leading-[1.15] tracking-[-0.025em] text-foreground [text-wrap:balance]">
                    <Link
                      to={`/blog/${post.slug}`}
                      data-stretched-link
                      className="transition-colors after:absolute after:inset-0 after:content-[''] focus-visible:outline-none group-hover:underline"
                    >
                      {post.title}
                    </Link>
                  </h3>
                  <p className="mt-2.5 max-w-2xl text-[1.0125rem] leading-relaxed text-muted-foreground">{post.lead}</p>
                  <TagList tags={post.tags} className="mt-4" />
                </article>
              </li>
            ))}
          </ul>
        )}
      </Band>
    </>
  );
}
