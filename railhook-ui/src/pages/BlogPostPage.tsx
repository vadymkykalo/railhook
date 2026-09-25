import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, ArrowRight, BookOpen, Radio } from 'lucide-react';
import { Button } from '../components/ui/button';
import Prose from '../components/blog/Prose';
import { BYLINE, TagList } from '../components/blog/PostMeta';
import { useAuth } from '../auth/auth.store';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { useJsonLd } from '../hooks/useJsonLd';
import { blogNeighbours, blogPost, type BlogPost } from '../lib/blog';
import { publicTesterEnabled } from '../lib/runtimeConfig';
import { siteUrl } from '../lib/siteUrl';
import { cn } from '../lib/utils';
import { blogDate, ReadingTime, useBlogDateFormat } from './BlogPage';
import { PageIntro, panel, WRAP } from './landing/primitives';

function NotFound() {
  const { t } = useTranslation();
  return (
    <PageIntro title={t('blog.notFound.title')} lead={t('blog.notFound.lead')}>
      <Button asChild variant="outline">
        <Link to="/blog">{t('blog.notFound.back')}</Link>
      </Button>
    </PageIntro>
  );
}

function Contents({ post }: { post: BlogPost }) {
  const { t } = useTranslation();
  if (post.document.headings.length < 2) return null;
  return (
    <nav aria-labelledby="blog-toc" className="sticky top-24 hidden max-h-[calc(100vh-8rem)] overflow-y-auto xl:block">
      <h2 id="blog-toc" className="mono-label mb-3">
        {t('blog.contents')}
      </h2>
      <ul className="space-y-2 border-l border-rail">
        {post.document.headings.map((heading) => (
          <li key={heading.id} className={heading.level === 3 ? 'pl-7' : 'pl-4'}>
            <a
              href={`#${heading.id}`}
              className="block text-[13px] leading-snug text-muted-foreground transition-colors hover:text-foreground"
            >
              {heading.text}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}

function CallToAction() {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();
  return (
    <aside className={cn('mt-14 max-w-[68ch] p-6 sm:p-7', panel())}>
      <h2 className="text-[1.5rem] font-normal tracking-[-0.02em] text-foreground">{t('blog.cta.title')}</h2>
      <p className="mt-2 text-[15px] leading-relaxed text-muted-foreground">{t('blog.cta.body')}</p>
      <div className="mt-5 flex flex-wrap items-center gap-3">
        <Button asChild className="max-sm:w-full">
          {isAuthenticated ? (
            <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
          ) : (
            <Link to="/register">{t('landing.hero.startFree')}</Link>
          )}
        </Button>
        <Button asChild variant="outline" className="max-sm:w-full">
          <a href="/docs/">
            <BookOpen className="h-4 w-4" aria-hidden="true" />
            {t('blog.cta.docs')}
          </a>
        </Button>
        {publicTesterEnabled() && (
          <Button asChild variant="outline" className="max-sm:w-full">
            <Link to="/tester">
              <Radio className="h-4 w-4" aria-hidden="true" />
              {t('blog.cta.tester')}
            </Link>
          </Button>
        )}
      </div>
    </aside>
  );
}

function Neighbours({ slug, language }: { slug: string; language: string }) {
  const { t } = useTranslation();
  const { previous, next } = blogNeighbours(slug, language);
  if (!previous && !next) return null;
  const card = 'flex flex-col gap-1 p-5 text-left';
  return (
    <nav aria-label={t('blog.moreReading')} className="mt-12 grid gap-4 sm:grid-cols-2">
      {previous ? (
        <Link to={`/blog/${previous.slug}`} className={cn(card, panel(true))}>
          <span className="mono-label flex items-center gap-1.5">
            <ArrowLeft className="h-3.5 w-3.5" aria-hidden="true" />
            {t('blog.previous')}
          </span>
          <span className="font-medium text-foreground">{previous.title}</span>
        </Link>
      ) : (
        <span aria-hidden="true" />
      )}
      {next && (
        <Link to={`/blog/${next.slug}`} className={cn(card, panel(true), 'sm:text-right')}>
          <span className="mono-label flex items-center gap-1.5 sm:justify-end">
            {t('blog.next')}
            <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
          </span>
          <span className="font-medium text-foreground">{next.title}</span>
        </Link>
      )}
    </nav>
  );
}

export default function BlogPostPage() {
  const { slug = '' } = useParams<{ slug: string }>();
  const { t, i18n } = useTranslation();
  const post = blogPost(slug, i18n.language);
  const dates = useBlogDateFormat();
  const site = siteUrl();

  // Hooks cannot be skipped, so an unknown slug describes itself as the blog index.
  useDocumentMeta(
    post
      ? { title: post.title, description: post.description, path: `/blog/${post.slug}`, image: post.image }
      : { titleKey: 'meta.blog.title', descriptionKey: 'meta.blog.description', path: '/blog' },
  );

  useJsonLd(
    'blog-post',
    post
      ? {
        '@context': 'https://schema.org',
        '@graph': [
          {
            '@type': 'Article',
            headline: post.title,
            description: post.description,
            datePublished: post.date,
            dateModified: post.sourcesCheckedOn ?? post.date,
            inLanguage: post.locale,
            keywords: post.tags.join(', '),
            image: `${site}${post.image}`,
            author: { '@type': 'Person', name: post.author },
            publisher: { '@type': 'Organization', name: 'Railhook', url: `${site}/` },
            mainEntityOfPage: { '@type': 'WebPage', '@id': `${site}/blog/${post.slug}` },
          },
          {
            '@type': 'BreadcrumbList',
            itemListElement: [
              { '@type': 'ListItem', position: 1, name: 'Railhook', item: `${site}/` },
              { '@type': 'ListItem', position: 2, name: t('blog.title'), item: `${site}/blog` },
              { '@type': 'ListItem', position: 3, name: post.title, item: `${site}/blog/${post.slug}` },
            ],
          },
        ],
      }
      : {},
  );

  if (!post) return <NotFound />;

  return (
    <div className={`${WRAP} pb-20 pt-10 sm:pt-14`}>
      <Link to="/blog" className="mono-label inline-flex min-h-10 items-center gap-1.5 hover:text-foreground">
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden="true" />
        {t('blog.allPosts')}
      </Link>

      <div className="mt-2 grid gap-12 xl:grid-cols-[minmax(0,1fr)_15rem]">
        {/* min-w-0: otherwise the table widens the grid column and the page scrolls sideways on a phone. */}
        <article className="min-w-0">
          <header className="max-w-[68ch]">
            <h1 className="text-[2rem] font-normal leading-[1.16] tracking-[-0.03em] text-foreground [text-wrap:balance] sm:text-[2.7rem]">
              {post.title}
            </h1>
            <p className="mt-4 text-[1.15rem] leading-relaxed text-muted-foreground">{post.lead}</p>
            <div className={cn(BYLINE, 'mt-5')}>
              <span className="text-foreground">{post.author}</span>
              <span aria-hidden="true">·</span>
              <time dateTime={post.date}>{dates.format(blogDate(post.date))}</time>
              <span aria-hidden="true">·</span>
              <ReadingTime minutes={post.readingMinutes} />
            </div>
            <TagList tags={post.tags} className="mt-4" />
            {post.sourcesCheckedOn && (
              <p className="mt-5 border-l-2 border-rail pl-4 text-[15px] leading-[1.65] text-muted-foreground">
                {t('blog.sourcesCheckedOn', { date: dates.format(blogDate(post.sourcesCheckedOn)) })}
              </p>
            )}
          </header>

          <div className="mt-10">
            <Prose blocks={post.document.blocks} />
          </div>

          <CallToAction />
          <Neighbours slug={post.slug} language={i18n.language} />
        </article>

        <Contents post={post} />
      </div>
    </div>
  );
}
