import { afterEach, describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import '../../i18n';
import i18n from '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';
import BlogPage from '../BlogPage';
import { blogPosts } from '../../lib/blog';

function renderBlog() {
  return renderPage(<BlogPage />, {
    path: '/blog',
    initialEntry: '/blog',
    auth: { user: null, token: null, isAuthenticated: false },
  });
}

const posts = blogPosts('en');

afterEach(async () => {
  await i18n.changeLanguage('en');
});

describe('BlogPage', () => {
  it('names the page for search: title, description and canonical', () => {
    renderBlog();
    expect(document.title).toBe(en.meta.blog.title);
    expect(document.head.querySelector('meta[name="description"]')?.getAttribute('content'))
      .toBe(en.meta.blog.description);
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toMatch(/\/blog$/);
  });

  it('lists every post, newest first, each linked at its own address', () => {
    renderBlog();
    const headings = screen.getAllByRole('heading', { level: 3 });
    expect(headings.map((h) => h.textContent)).toEqual(posts.map((p) => p.title));
    for (const post of posts) {
      expect(screen.getByRole('link', { name: post.title })).toHaveAttribute('href', `/blog/${post.slug}`);
    }
  });

  it('shows each post’s date, reading time and lead, with the title as its one link', () => {
    renderBlog();
    for (const post of posts) {
      const link = screen.getByRole('link', { name: post.title });
      const article = link.closest('article') as HTMLElement;
      expect(within(article).getAllByRole('link')).toEqual([link]);
      expect(within(article).getByText(post.lead)).toBeInTheDocument();
      expect(article.querySelector(`time[datetime="${post.date}"]`)).not.toBeNull();
      expect(article.textContent).toContain(en.blog.readingTime.replace('{{minutes}}', String(post.readingMinutes)));
    }
  });

  it('announces the feed in the head and links it on the page', () => {
    renderBlog();
    const alternate = document.head.querySelector('link[rel="alternate"][type="application/rss+xml"]');
    expect(alternate).not.toBeNull();
    expect(alternate).toHaveAttribute('href', '/blog/rss.xml');
    expect(alternate).toHaveAttribute('title', en.blog.feedTitle);
    expect(screen.getByRole('link', { name: en.blog.feed })).toHaveAttribute('href', '/blog/rss.xml');
  });

  it('takes the feed link out of the head again when the reader leaves', () => {
    const { unmount } = renderBlog();
    unmount();
    expect(document.head.querySelector('link[rel="alternate"][type="application/rss+xml"]')).toBeNull();
  });

  it('publishes the index as schema.org Blog data, one entry per post', () => {
    renderBlog();
    const block = document.head.querySelector('script[data-page="blog"]');
    expect(block).not.toBeNull();
    const data = JSON.parse(block!.textContent ?? '{}');
    expect(data['@type']).toBe('Blog');
    expect(data.blogPost.map((p: { headline: string }) => p.headline)).toEqual(posts.map((p) => p.title));
    expect(data.blogPost[0].url).toMatch(new RegExp(`/blog/${posts[0].slug}$`));
  });

  it('shows the Ukrainian titles to a Ukrainian reader', async () => {
    await i18n.changeLanguage('uk');
    renderBlog();
    const ukrainian = blogPosts('uk');
    expect(screen.getByRole('link', { name: ukrainian[0].title })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: posts[0].title })).toBeNull();
  });
});
