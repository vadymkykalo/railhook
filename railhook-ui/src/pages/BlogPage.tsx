import { useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Rss } from 'lucide-react';
import { BYLINE } from '../components/blog/PostMeta';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { useJsonLd } from '../hooks/useJsonLd';
import { blogPosts } from '../lib/blog';
import { siteUrl } from '../lib/siteUrl';
import { Band, PageIntro } from './landing/primitives';

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
      <PageIntro title={t('blog.title')} lead={t('blog.lead')}>
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
          <ul className="border-t border-rail">
            {posts.map((post) => (
              <li key={post.slug}>
                <article className="border-b border-rail py-6">
                  <div className={BYLINE}>
                    <time dateTime={post.date}>{dates.format(blogDate(post.date))}</time>
                    <span aria-hidden="true">·</span>
                    <ReadingTime minutes={post.readingMinutes} />
                  </div>
                  <h3 className="mt-2 text-[1.3rem] font-medium leading-[1.2] tracking-[-0.02em] text-foreground [text-wrap:balance]">
                    <Link to={`/blog/${post.slug}`} className="hover:underline">
                      {post.title}
                    </Link>
                  </h3>
                  <p className="mt-2 max-w-2xl leading-relaxed text-muted-foreground">{post.lead}</p>
                </article>
              </li>
            ))}
          </ul>
        )}
      </Band>
    </>
  );
}
