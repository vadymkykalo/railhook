/**
 * What a link to the docs looks like away from the docs: the image a chat or a card preview
 * shows, and the date a crawler reads off the sitemap.
 *
 * Both are absolute or dated, which the rest of the build avoids: the image is built once for
 * every deployment. So the image URL uses the placeholder origin that nginx rewrites to the
 * container's RAILHOOK_SITE_URL, as `site` in astro.config.mjs does.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const SITE_PLACEHOLDER = 'https://site-url.railhook.invalid';

/** The site's own og-image.png, served by the same nginx at the root. */
const SOCIAL_IMAGE = `${SITE_PLACEHOLDER}/og-image.png`;

/** Starlight declares `twitter:card summary_large_image` on every page; this is the image it promises. */
export const socialImageHead = [
  { tag: 'meta', attrs: { property: 'og:image', content: SOCIAL_IMAGE } },
  { tag: 'meta', attrs: { name: 'twitter:image', content: SOCIAL_IMAGE } },
];

const DOCS_DIR = fileURLToPath(new URL('../src/content/docs/', import.meta.url));

/** The content file a built page came from, or undefined for a page with none (the API reference, 404). */
export function pageFileFor(url) {
  const slug = new URL(url).pathname.replace(/^\/docs\/?/, '').replace(/\/$/, '');
  const candidates = slug
    ? [`${slug}.mdx`, `${slug}.md`, `${slug}/index.mdx`, `${slug}/index.md`]
    : ['index.mdx', 'index.md'];
  return candidates.map((candidate) => DOCS_DIR + candidate).find((file) => existsSync(file));
}

const BUILD_TIME = new Date();

/**
 * When a page last changed: its last commit, or its file's own time where there is no git —
 * the image build copies no .git — and the build time for a page with no file behind it.
 */
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
    // No git here; the file's modification time is the next best answer.
  }
  return statSync(file).mtime;
}
