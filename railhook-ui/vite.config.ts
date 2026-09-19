/// <reference types="vitest" />
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import pkg from './package.json' with { type: 'json' }
import { parseFrontMatter } from './src/lib/frontMatter'
import { renderFeed, type FeedItem } from './src/lib/blogFeed'

/**
 * The repository's CHANGELOG.md as `virtual:changelog`, for the /changelog page.
 *
 * A virtual module rather than a `?raw` import of `../CHANGELOG.md`: the file sits outside this
 * app, and Vite's dev server would only serve it with the whole repository, `.env` included, on
 * its allow list. The Dockerfile copies the file next to the app for the same reason.
 */
function changelog(): Plugin {
  const id = 'virtual:changelog'
  const resolved = `\0${id}`
  const file = fileURLToPath(new URL('../CHANGELOG.md', import.meta.url))
  return {
    name: 'railhook-changelog',
    resolveId: (source) => (source === id ? resolved : undefined),
    load(loaded) {
      if (loaded !== resolved) return undefined
      this.addWatchFile(file)
      return `export default ${JSON.stringify(readFileSync(file, 'utf8'))};`
    },
  }
}


/**
 * The blog's feed, written to `dist/blog/rss.xml` at build time.
 *
 * Read from the same directory and through the same front-matter parser `src/lib/blog.ts` uses,
 * so a post that is on the site is in the feed. Not generated from the running app, because a
 * feed a reader subscribes to has to be a file nginx can serve without JavaScript.
 *
 * What the XML says lives in `src/lib/blogFeed.ts`, which is where it can be tested; this reads
 * the files and emits the asset.
 *
 * The origin is the placeholder every other published URL carries: the image is built once for
 * every deployment, and nginx substitutes the container's RAILHOOK_SITE_URL when it serves the
 * file (`location /` filters text/xml for exactly this).
 */
function blogRss(): Plugin {
  const SITE = 'https://site-url.railhook.invalid'
  const dir = fileURLToPath(new URL('./src/content/blog', import.meta.url))

  function feed(): string {
    if (!existsSync(dir)) return renderFeed(SITE, [])
    const items = readdirSync(dir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && existsSync(join(dir, entry.name, 'en.md')))
      .map((entry) => ({
        slug: entry.name,
        ...parseFrontMatter(readFileSync(join(dir, entry.name, 'en.md'), 'utf8')).values,
      }))
      .filter((item): item is FeedItem => Boolean(item.title && item.date && item.description))
      .sort((a, b) => b.date.localeCompare(a.date) || a.slug.localeCompare(b.slug))
    return renderFeed(SITE, items)
  }

  return {
    name: 'railhook-blog-rss',
    // Dev serves it from the route the build writes to, so a broken feed is found under
    // `npm run dev` rather than after a deploy.
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/blog/rss.xml') return next()
        res.setHeader('Content-Type', 'application/rss+xml; charset=utf-8')
        res.end(feed())
      })
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'blog/rss.xml', source: feed() })
    },
  }
}

export default defineConfig({
  plugins: [react(), changelog(), blogRss()],
  define: {
    // Which build an error came from. package.json's version is one of the seven places
    // `make version-set` writes and `make version-check` verifies, so this cannot drift from
    // the release it belongs to.
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    // The app calls /api/v1/... on its own origin, because in production nginx
    // serves the bundle and the API from one host. In dev there is no nginx, so
    // without this every API call lands on Vite itself and comes back 404 —
    // which the UI renders as "the requested resource was not found", sending
    // you looking for a bug in the endpoint rather than at a missing backend.
    // VITE_API_URL still wins when set; this is only the zero-config default.
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
      // The docs are a site of their own (railhook-docs/, `make docs-dev` serves it on 4321
      // under the same /docs base). In production nginx serves both from one origin; this
      // keeps the dashboard's /docs/ links working under `npm run dev`.
      '/docs': {
        target: 'http://localhost:4321',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    css: false,
    // v8 provider (no extra native instrumentation step, and it's
    // already a dependency of the vitest version pinned here) - reporters
    // chosen so a human (text), a PR artifact viewer (html) and a badge/CI
    // gate (json-summary, lcov) all have a format to read.
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov', 'json-summary'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.d.ts',
        'src/**/*.{test,spec}.{ts,tsx}',
        'src/test/**',
        'src/main.tsx',
        'src/vite-env.d.ts',
        'src/i18n/locales/**',
        'src/types/**',
      ],
      // Baseline (recorded 2026-08-21):
      //   lines 16.38% / statements 16.38% / functions 18.63% / branches 58.04%
      // Set a couple points below that measured baseline (not aspirationally
      // high - gates nobody respects don't get respected) so normal
      // coverage-tool jitter doesn't redden the build, while still catching
      // an actual regression. Only 11 test files exist today against ~150
      // source files - ratchet these up as more UI tests land, don't
      // leave them here.
      //
      // Re-baseline (recorded 2026-08-22, same test files/assertions -
      // no coverage regression): bumping @vitest/coverage-v8 1.6.1 -> 3.2.4
      // (alongside vitest/vite themselves) changed the v8 provider's
      // line/statement remapping and measured lines/statements at 13.35%
      // against the exact same suite that measured 16.38% before - functions
      // (20.06%) and branches (59.58%) barely moved, so this is instrumentation
      // methodology, not lost coverage. Lowered lines/statements to stay a
      // couple points under the new number; left functions/branches alone
      // since they still clear the old thresholds comfortably.
      //
      // Re-baseline (recorded 2026-09-12): @vitest/coverage-v8 3.2.4 -> 4.1.11
      // (the vitest 4 line, taken for the @vitest/mocker path-traversal
      // advisory). v4 makes AST-aware remapping the default and drops the
      // toggle, so every metric is now measured against syntax rather than
      // against v8's line ranges - the same 59 files and 472 tests that
      // measured 54.28/74.11/43.95 under 3.2.4 measure 42.49/36.64/34.82
      // under 4.1.11. Branches moves most because v8 used to score a whole
      // uncovered ternary or ?? chain as one taken branch. Numbers below are
      // set a few points under each new measurement; they are NOT comparable
      // with the ones in the paragraph above, and the direction of travel is
      // still up as UI tests land.
      thresholds: {
        lines: 38,
        statements: 36,
        functions: 30,
        branches: 32,
      },
    },
  }
})
