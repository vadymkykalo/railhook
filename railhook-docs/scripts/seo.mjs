import { execFileSync } from 'node:child_process';
import { existsSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const SITE_PLACEHOLDER = 'https://site-url.railhook.invalid';

const SOCIAL_IMAGE = `${SITE_PLACEHOLDER}/social-card.png`;

// Starlight declares `twitter:card summary_large_image` but names no image.
export const socialImageHead = [
  { tag: 'meta', attrs: { property: 'og:image', content: SOCIAL_IMAGE } },
  { tag: 'meta', attrs: { name: 'twitter:image', content: SOCIAL_IMAGE } },
];

const DOCS_DIR = fileURLToPath(new URL('../src/content/docs/', import.meta.url));

export function pageFileFor(url) {
  const slug = new URL(url).pathname.replace(/^\/docs\/?/, '').replace(/\/$/, '');
  const candidates = slug
    ? [`${slug}.mdx`, `${slug}.md`, `${slug}/index.mdx`, `${slug}/index.md`]
    : ['index.mdx', 'index.md'];
  return candidates.map((candidate) => DOCS_DIR + candidate).find((file) => existsSync(file));
}

const BUILD_TIME = new Date();

// The image build copies no .git, so there the file's mtime stands in for the last commit.
export function lastmodFor(url, built = BUILD_TIME) {
  const file = pageFileFor(url);
  if (!file) return built;
  try {
    const committed = execFileSync('git', ['log', '-1', '--format=%cI', '--', file], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
    if (committed) return new Date(committed);
  } catch {
  }
  return statSync(file).mtime;
}
