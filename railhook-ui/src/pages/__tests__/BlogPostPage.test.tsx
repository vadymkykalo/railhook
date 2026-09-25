import { afterEach, describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import '../../i18n';
import i18n from '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';
import BlogPostPage from '../BlogPostPage';
import { blogPosts } from '../../lib/blog';

/** Asserts on the newest real post, not a fixture. */
const POST = blogPosts('en')[0];

const PROVIDERS = 'stripe-github-shopify-when-your-endpoint-is-down';

function renderPost(slug = POST.slug, signedIn = false) {
  return renderPage(<BlogPostPage />, {
    path: '/blog/:slug',
    initialEntry: `/blog/${slug}`,
    ...(signedIn ? {} : { auth: { user: null, token: null, isAuthenticated: false } }),
  });
}

afterEach(async () => {
  await i18n.changeLanguage('en');
  delete window.__RAILHOOK__;
});

describe('BlogPostPage', () => {
  it('leads with the title, the lead and the byline', () => {
    renderPost();
    expect(screen.getByRole('heading', { level: 1, name: POST.title })).toBeInTheDocument();
    expect(screen.getByText(POST.lead)).toBeInTheDocument();
    expect(screen.getByText(POST.author)).toBeInTheDocument();
    expect(document.querySelector(`time[datetime="${POST.date}"]`)).not.toBeNull();
    expect(document.body.textContent)
      .toContain(en.blog.readingTime.replace('{{minutes}}', String(POST.readingMinutes)));
  });

  it('describes itself for search and for a link unfurler, with its own social card', () => {
    renderPost();
    expect(document.title).toBe(POST.title);
    expect(document.head.querySelector('meta[name="description"]')?.getAttribute('content')).toBe(POST.description);
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href'))
      .toMatch(new RegExp(`/blog/${POST.slug}$`));
    expect(document.head.querySelector('meta[property="og:image"]')?.getAttribute('content'))
      .toMatch(new RegExp(`/blog/${POST.slug}\\.png$`));
  });

  it('publishes Article and BreadcrumbList data naming the post, the blog and the site', () => {
    renderPost();
    const block = document.head.querySelector('script[data-page="blog-post"]');
    expect(block).not.toBeNull();
    const graph = JSON.parse(block!.textContent ?? '{}')['@graph'] as Record<string, unknown>[];
    const article = graph.find((node) => node['@type'] === 'Article')!;
    expect(article.headline).toBe(POST.title);
    expect(article.datePublished).toBe(POST.date);
    expect((article.author as { name: string }).name).toBe(POST.author);

    const crumbs = graph.find((node) => node['@type'] === 'BreadcrumbList')!;
    const names = (crumbs.itemListElement as { name: string }[]).map((item) => item.name);
    expect(names).toEqual(['Railhook', en.blog.title, POST.title]);
  });

  it('renders the file’s markup as elements, never as raw markdown', () => {
    renderPost();
    const article = screen.getByRole('heading', { level: 1 }).closest('article') as HTMLElement;
    const prose = article.cloneNode(true) as HTMLElement;
    prose.querySelectorAll('code, pre').forEach((node) => node.remove());
    const text = prose.textContent ?? '';
    expect(text).not.toMatch(/\*\*/);
    expect(text).not.toMatch(/\]\(/);
    expect(text).not.toMatch(/^## /m);
    expect(text).not.toContain(':::figure');
    expect(within(article).getAllByRole('heading', { level: 2 }).length).toBeGreaterThan(2);
  });

  it('draws its diagrams, each with a text alternative and a caption', () => {
    renderPost();
    const figures = screen.getAllByRole('figure');
    expect(figures.length).toBeGreaterThanOrEqual(2);
    for (const figure of figures) {
      expect(within(figure).getByRole('img').getAttribute('aria-label')?.length).toBeGreaterThan(40);
      expect(figure.querySelector('figcaption')?.textContent).toBeTruthy();
    }
  });

  it('draws the provider timeline in the vendors’ own bundled logos', () => {
    renderPost(PROVIDERS);
    const timeline = screen.getByRole('img', { name: en.blog.figures.providerRetries.aria });
    const logos = Array.from(timeline.querySelectorAll('image')).map((image) => image.getAttribute('href'));
    for (const name of ['stripe', 'github', 'shopify']) {
      expect(logos, `the timeline should draw the ${name} logo`).toContain(`/logos/brand/${name}.svg`);
    }
  });

  it('compares the providers in a table, with their logos and links to their own docs', () => {
    renderPost(PROVIDERS);
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('columnheader').length).toBeGreaterThanOrEqual(4);
    expect(within(table).getAllByRole('row').length).toBe(4);
    const logos = Array.from(table.querySelectorAll('img')).map((img) => img.getAttribute('src'));
    expect(logos).toEqual(expect.arrayContaining([
      '/logos/brand/stripe.svg',
      '/logos/brand/github.svg',
      '/logos/brand/shopify.svg',
    ]));

    const article = screen.getByRole('heading', { level: 1 }).closest('article') as HTMLElement;
    const external = within(article).getAllByRole('link').map((a) => a.getAttribute('href') ?? '')
      .filter((href) => href.startsWith('http'));
    for (const host of ['docs.stripe.com', 'docs.github.com', 'shopify.dev']) {
      expect(external.some((href) => href.includes(host)), `a link to ${host}`).toBe(true);
    }
    for (const link of within(article).getAllByRole('link')) {
      if (link.getAttribute('href')?.startsWith('http')) {
        expect(link).toHaveAttribute('rel', 'noopener noreferrer');
      }
    }
  });

  it('says when the provider figures were last checked', () => {
    renderPost();
    expect(POST.sourcesCheckedOn).toBeTruthy();
    expect(document.body.textContent).toContain(en.blog.sourcesCheckedOn.split('{{date}}')[0]);
  });

  it('offers a table of contents built from the article’s own headings', () => {
    renderPost();
    const toc = screen.getByRole('navigation', { name: en.blog.contents });
    const anchors = within(toc).getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(anchors).toEqual(POST.document.headings.map((h) => `#${h.id}`));
    for (const heading of POST.document.headings) {
      expect(document.getElementById(heading.id), heading.id).not.toBeNull();
    }
  });

  it('ends with the way in and the docs, and offers the tester only where there is one', () => {
    renderPost();
    expect(screen.getByRole('link', { name: en.landing.hero.startFree })).toHaveAttribute('href', '/register');
    expect(screen.getByRole('link', { name: en.blog.cta.docs })).toHaveAttribute('href', '/docs/');
    expect(screen.queryByRole('link', { name: en.blog.cta.tester })).toBeNull();
  });

  it('offers the tester where the deployment has one', () => {
    window.__RAILHOOK__ = { publicTester: true };
    renderPost();
    expect(screen.getByRole('link', { name: en.blog.cta.tester })).toHaveAttribute('href', '/tester');
  });

  it('sends a signed-in reader to the dashboard rather than to the signup', () => {
    renderPost(POST.slug, true);
    expect(screen.getByRole('link', { name: en.landing.nav.goToDashboard })).toHaveAttribute('href', '/admin/dashboard');
    expect(screen.queryByRole('link', { name: en.landing.hero.startFree })).toBeNull();
  });

  it('offers the way back to the index', () => {
    renderPost();
    expect(screen.getByRole('link', { name: en.blog.allPosts })).toHaveAttribute('href', '/blog');
  });

  it('shows the Ukrainian translation to a Ukrainian reader', async () => {
    await i18n.changeLanguage('uk');
    renderPost();
    const ukrainian = blogPosts('uk').find((post) => post.slug === POST.slug)!;
    expect(screen.getByRole('heading', { level: 1, name: ukrainian.title })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe('uk');
  });

  it('says so, and points back at the blog, when the address matches no post', () => {
    renderPost('no-such-post');
    expect(screen.getByRole('heading', { level: 1, name: en.blog.notFound.title })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: en.blog.notFound.back })).toHaveAttribute('href', '/blog');
  });
});
